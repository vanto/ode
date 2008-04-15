/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ode.axis2;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import javax.wsdl.Definition;
import javax.wsdl.Fault;
import javax.wsdl.Operation;
import javax.xml.namespace.QName;

import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.client.OperationClient;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.axis2.util.SoapMessageConverter;
import org.apache.ode.bpel.epr.EndpointFactory;
import org.apache.ode.bpel.epr.MutableEndpoint;
import org.apache.ode.bpel.epr.WSAEndpoint;
import org.apache.ode.bpel.iapi.BpelServer;
import org.apache.ode.bpel.iapi.Message;
import org.apache.ode.bpel.iapi.MessageExchange;
import org.apache.ode.bpel.iapi.PartnerRoleMessageExchange;
import org.apache.ode.bpel.iapi.Scheduler;
import org.apache.ode.bpel.iapi.MessageExchange.FailureType;
import org.apache.ode.il.OMUtils;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.Namespaces;
import org.apache.ode.utils.wsdl.*;
import org.apache.ode.utils.wsdl.Messages;
import org.apache.ode.utils.uuid.UUID;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Acts as a service not provided by ODE. Used mainly for invocation as a way to maintain the WSDL description of used
 * services.
 *
 * @author Matthieu Riou <mriou at apache dot org>
 */
public class SoapExternalService implements ExternalService {

    private static final Log __log = LogFactory.getLog(SoapExternalService.class);

    private static final org.apache.ode.utils.wsdl.Messages msgs = Messages.getMessages(Messages.class);


    private static final int EXPIRE_SERVICE_CLIENT = 30000;

    private static ThreadLocal<CachedServiceClient> _cachedClients = new ThreadLocal<CachedServiceClient>();

    private ExecutorService _executorService;
    private Definition _definition;
    private QName _serviceName;
    private String _portName;
    private AxisConfiguration _axisConfig;
    private SoapMessageConverter _converter;
    private Scheduler _sched;
    private BpelServer _server;

    public SoapExternalService(Definition definition, QName serviceName, String portName, ExecutorService executorService,
            AxisConfiguration axisConfig, Scheduler sched, BpelServer server) throws AxisFault {
        _definition = definition;
        _serviceName = serviceName;
        _portName = portName;
        _executorService = executorService;
        _axisConfig = axisConfig;
        _sched = sched;
        _converter = new SoapMessageConverter(definition, serviceName, portName);
        _server = server;
    }

    public void invoke(final PartnerRoleMessageExchange odeMex) {
        boolean isTwoWay = odeMex.getMessageExchangePattern() == org.apache.ode.bpel.iapi.MessageExchange.MessageExchangePattern.REQUEST_RESPONSE;
        try {
            // Override options are passed to the axis MessageContext so we can
            // retrieve them in our session out handler.
            MessageContext mctx = new MessageContext();
            writeHeader(mctx, odeMex);

            _converter.createSoapRequest(mctx, odeMex.getRequest(), odeMex.getOperation());

            SOAPEnvelope soapEnv = mctx.getEnvelope();
            EndpointReference axisEPR = new EndpointReference(((MutableEndpoint) odeMex.getEndpointReference())
                    .getUrl());
            if (__log.isDebugEnabled()) {
                __log.debug("Axis2 sending message to " + axisEPR.getAddress() + " using MEX " + odeMex);
                __log.debug("Message: " + soapEnv);
            }

            Options options = new Options();
            options.setAction(mctx.getSoapAction());
            options.setTo(axisEPR);
            options.setTimeOutInMilliSeconds(60000);
            options.setExceptionToBeThrownOnSOAPFault(false);

            AuthenticationHelper.setHttpAuthentication(odeMex, options);

            CachedServiceClient cached = _cachedClients.get();
            long now = System.currentTimeMillis();
            if (cached == null || cached._expire < now) {
                cached = new CachedServiceClient();
                ConfigurationContext ctx = new ConfigurationContext(_axisConfig);
                cached._client = new ServiceClient(ctx, null);
                cached._expire = now+EXPIRE_SERVICE_CLIENT;
                _cachedClients.set(cached);
            }
            final OperationClient operationClient = cached._client.createClient(isTwoWay ? ServiceClient.ANON_OUT_IN_OP
                    : ServiceClient.ANON_OUT_ONLY_OP);
            operationClient.setOptions(options);
            operationClient.addMessageContext(mctx);

            if (isTwoWay) {
                final String mexId = odeMex.getMessageExchangeId();
                final Operation operation = odeMex.getOperation();

                // Defer the invoke until the transaction commits.
                _sched.registerSynchronizer(new Scheduler.Synchronizer() {
                    public void afterCompletion(boolean success) {
                        // If the TX is rolled back, then we don't send the request.
                        if (!success) return;

                        // The invocation must happen in a separate thread, holding on the afterCompletion
                        // blocks other operations that could have been listed there as well.
                        _executorService.submit(new Callable<Object>() {
                            public Object call() throws Exception {
                                try {
                                    operationClient.execute(true);
                                    MessageContext response = operationClient.getMessageContext(WSDLConstants.MESSAGE_LABEL_IN_VALUE);
                                    MessageContext flt = operationClient.getMessageContext(WSDLConstants.MESSAGE_LABEL_FAULT_VALUE);
                                    if (response != null && __log.isDebugEnabled())
                                        __log.debug("Service response:\n" + response.getEnvelope().toString());

                                    if (flt != null) {
                                        reply(mexId, operation, flt, true);
                                    } else {
                                        reply(mexId, operation, response, response.isFault());
                                    }
                                } catch (Throwable t) {
                                    String errmsg = "Error sending message (mex=" + odeMex + "): " + t.getMessage();
                                    __log.error(errmsg, t);
                                    replyWithFailure(mexId, MessageExchange.FailureType.COMMUNICATION_ERROR, errmsg, null);
                                }
                                return null;
                            }
                        });
                    }
                    public void beforeCompletion() {
                    }
                });
                odeMex.replyAsync();

            } else { /** one-way case * */
                _executorService.submit(new Callable<Object>() {
                    public Object call() throws Exception {
                        operationClient.execute(false);
                        return null;
                    }
                });
                odeMex.replyOneWayOk();
            }
        } catch (AxisFault axisFault) {
            String errmsg = "Error sending message to Axis2 for ODE mex " + odeMex;
            __log.error(errmsg, axisFault);
            odeMex.replyWithFailure(MessageExchange.FailureType.COMMUNICATION_ERROR, errmsg, null);
        }
    }

    /**
     * Extracts the action to be used for the given operation.  It first checks to see
     * if a value is specified using WS-Addressing in the portType, it then falls back onto 
     * getting it from the SOAP Binding.
     * @param operation the name of the operation to get the Action for
     * @return The action value for the specified operation
     */
    private String getAction(String operation)
	{
    	String action = _converter.getWSAInputAction(operation);
        if (action == null || "".equals(action))
        {
        	action = _converter.getSoapAction(operation);	
        }
		return action;
	}

	/**
     * Extracts endpoint information from ODE message exchange to stuff them into Axis MessageContext.
     */
    private void writeHeader(MessageContext ctxt, PartnerRoleMessageExchange odeMex) {
        Options options = ctxt.getOptions();
        WSAEndpoint targetEPR = EndpointFactory.convertToWSA((MutableEndpoint) odeMex.getEndpointReference());
        WSAEndpoint myRoleEPR = EndpointFactory.convertToWSA((MutableEndpoint) odeMex.getMyRoleEndpointReference());

        String partnerSessionId = odeMex.getProperty(MessageExchange.PROPERTY_SEP_PARTNERROLE_SESSIONID);
        String myRoleSessionId = odeMex.getProperty(MessageExchange.PROPERTY_SEP_MYROLE_SESSIONID);
    
        if (partnerSessionId != null) {
            if (__log.isDebugEnabled()) {
                __log.debug("Partner session identifier found for WSA endpoint: " + partnerSessionId);
            }
            targetEPR.setSessionId(partnerSessionId);
        }
        options.setProperty("targetSessionEndpoint", targetEPR);
        
        if (myRoleEPR != null) {
            if (myRoleSessionId != null) {
                if (__log.isDebugEnabled()) {
                    __log.debug("MyRole session identifier found for myrole (callback) WSA endpoint: "
                            + myRoleSessionId);
                }
                myRoleEPR.setSessionId(myRoleSessionId);
            }
            options.setProperty("callbackSessionEndpoint", odeMex.getMyRoleEndpointReference());
        } else {
            __log.debug("My-Role EPR not specified, SEP will not be used.");
        }

        String action = getAction(odeMex.getOperationName());
        ctxt.setSoapAction(action);
        
	    if (MessageExchange.MessageExchangePattern.REQUEST_RESPONSE == odeMex.getMessageExchangePattern()) {
	    	EndpointReference annonEpr =
	    		new EndpointReference(Namespaces.WS_ADDRESSING_ANON_URI);
	    	ctxt.setReplyTo(annonEpr);
	    	ctxt.setMessageID("uuid:" + new UUID().toString());
	    }
    }

    public org.apache.ode.bpel.iapi.EndpointReference getInitialEndpointReference() {
        Element eprElmt = ODEService.genEPRfromWSDL(_definition, _serviceName, _portName);
        if (eprElmt == null)
            throw new IllegalArgumentException(msgs.msgPortDefinitionNotFound(_serviceName, _portName));
        return EndpointFactory.convertToWSA(ODEService.createServiceRef(eprElmt));
    }

    public void close() {
        // nothing
    }

    public String getPortName() {
        return _portName;
    }

    public QName getServiceName() {
        return _serviceName;
    }

    private void replyWithFailure(final String odeMexId, final FailureType error, final String errmsg,
            final Element details) {
        // ODE MEX needs to be invoked in a TX.
        try {
            _sched.execIsolatedTransaction(new Callable<Void>() {
                public Void call() throws Exception {
                    PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange)  _server.getEngine().getMessageExchange(odeMexId);
                    odeMex.replyWithFailure(error, errmsg, details);
                    return null;
                }
            });

        } catch (Exception e) {
            String emsg = "Error executing replyWithFailure transaction; reply will be lost.";
            __log.error(emsg, e);

        }

    }

    private void reply(final String odeMexId, final Operation operation, final MessageContext reply, final boolean fault) {
        // ODE MEX needs to be invoked in a TX.
        try {
            _sched.execIsolatedTransaction(new Callable<Void>() {
                public Void call() throws Exception {
                    PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange)  _server.getEngine().getMessageExchange(odeMexId);
                    // Setting the response
                    try {
                        if (__log.isDebugEnabled()) __log.debug("Received response for MEX " + odeMex);
                        if (fault) {
                            Document odeMsg = DOMUtils.newDocument();
                            Element odeMsgEl = odeMsg.createElementNS(null, "message");
                            odeMsg.appendChild(odeMsgEl);
                            QName faultType = _converter.parseSoapFault(odeMsgEl, reply.getEnvelope(), operation);
                            if (__log.isDebugEnabled()) __log.debug("Reply is a fault, found type: " + faultType);

                            if (faultType != null) {
                                if (__log.isWarnEnabled())
                                    __log.warn("Fault response: faultType=" + faultType + "\n" + DOMUtils.domToString(odeMsgEl));
                                QName nonNullFT = new QName(Namespaces.ODE_EXTENSION_NS, "unknownFault");
                                Fault f = odeMex.getOperation().getFault(faultType.getLocalPart());
                                if (f != null && f.getMessage().getQName() != null) nonNullFT = f.getMessage().getQName();
                                else __log.debug("Fault " + faultType + " isn't referenced in the service definition, unknown fault.");

                                Message response = odeMex.createMessage(nonNullFT);
                                response.setMessage(odeMsgEl);

                                odeMex.replyWithFault(faultType, response);
                            } else {
                                if (__log.isWarnEnabled())
                                    __log.warn("Fault response: faultType=(unkown)\n" + reply.getEnvelope().toString());
                                odeMex.replyWithFailure(FailureType.OTHER, reply.getEnvelope().getBody()
                                        .getFault().getText(), OMUtils.toDOM(reply.getEnvelope().getBody()));
                            }
                        } else {
                            Message response = odeMex.createMessage(odeMex.getOperation().getOutput().getMessage().getQName());
                            _converter.parseSoapResponse(response, reply.getEnvelope(), operation);
                            if (__log.isInfoEnabled()) __log.info("Response:\n" + (response.getMessage() != null ?
                                    DOMUtils.domToString(response.getMessage()) : "empty"));
                            odeMex.reply(response);
                        }
                    } catch (Exception ex) {
                        String errmsg = "Unable to process response: " + ex.getMessage();
                        __log.error(errmsg, ex);
                        odeMex.replyWithFailure(FailureType.OTHER, errmsg, null);
                    }
                    return null;
                }
            });

        } catch (Exception e) {
            String errmsg = "Error executing reply transaction; reply will be lost.";
            __log.error(errmsg, e);
        }
    }

    // INNER CLASS
    static class CachedServiceClient {
        ServiceClient _client;
        long _expire;
    }

}