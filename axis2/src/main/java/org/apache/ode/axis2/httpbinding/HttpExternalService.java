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

package org.apache.ode.axis2.httpbinding;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.axis2.ExternalService;
import org.apache.ode.axis2.ODEService;
import org.apache.ode.bpel.epr.EndpointFactory;
import org.apache.ode.bpel.epr.WSAEndpoint;
import org.apache.ode.bpel.iapi.BpelServer;
import org.apache.ode.bpel.iapi.EndpointReference;
import org.apache.ode.bpel.iapi.MessageExchange;
import org.apache.ode.bpel.iapi.PartnerRoleMessageExchange;
import org.apache.ode.bpel.iapi.Scheduler;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.Namespaces;
import org.apache.ode.utils.wsdl.Messages;
import org.apache.ode.utils.wsdl.WsdlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.wsdl.Binding;
import javax.wsdl.Definition;
import javax.wsdl.Operation;
import javax.wsdl.Part;
import javax.wsdl.Port;
import javax.wsdl.Service;
import javax.xml.namespace.QName;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;


/**
 * @author <a href="mailto:midon@intalio.com">Alexis Midon</a>
 */
public class HttpExternalService implements ExternalService {

    private static final Log log = LogFactory.getLog(HttpExternalService.class);
    private static final Messages msgs = Messages.getMessages(Messages.class);

    private MultiThreadedHttpConnectionManager connections = new MultiThreadedHttpConnectionManager();

    protected ExecutorService executorService;
    protected Scheduler scheduler;
    protected BpelServer server;
    protected QName serviceName;
    protected String portName;

    protected HttpMethodBuilder methodBuilder;
    protected WSAEndpoint endpointReference;

    public HttpExternalService(Definition definition, QName serviceName, String portName, ExecutorService executorService, Scheduler scheduler, BpelServer server) {
        this.portName = portName;
        this.serviceName = serviceName;
        this.executorService = executorService;
        this.scheduler = scheduler;
        this.server = server;

        Service serviceDef = definition.getService(serviceName);
        if (serviceDef == null)
            throw new IllegalArgumentException(msgs.msgServiceDefinitionNotFound(serviceName));
        Port port = serviceDef.getPort(portName);
        if (port == null)
            throw new IllegalArgumentException(msgs.msgPortDefinitionNotFound(serviceName, portName));
        Binding binding = port.getBinding();
        if (binding == null)
            throw new IllegalArgumentException(msgs.msgBindingNotFound(portName));

        // validate the http binding
        if (!WsdlUtils.useHTTPBinding(port)) {
            throw new IllegalArgumentException(msgs.msgNoHTTPBindingForPort(portName));
        }
        // throws an IllegalArgumentException if not valid
        new HttpBindingValidator(binding).validate();

        // initial endpoint reference
        Element eprElmt = ODEService.genEPRfromWSDL(definition, serviceName, portName);
        if (eprElmt == null)
            throw new IllegalArgumentException(msgs.msgPortDefinitionNotFound(serviceName, portName));
        endpointReference = EndpointFactory.convertToWSA(ODEService.createServiceRef(eprElmt));

        methodBuilder = new HttpMethodBuilder(binding);
    }

    public String getPortName() {
        return portName;
    }

    public QName getServiceName() {
        return serviceName;
    }

    public void close() {
        connections.shutdown();
    }

    public EndpointReference getInitialEndpointReference() {
        return endpointReference;
    }

    public void invoke(PartnerRoleMessageExchange odeMex) {
        try {
            // build the http method
            final HttpMethod method = methodBuilder.buildHttpMethod(odeMex);
            // this callable encapsulate the http method execution and the process of the response
            final Callable executionCallable;

            // execute it
            boolean isTwoWay = odeMex.getMessageExchangePattern() == org.apache.ode.bpel.iapi.MessageExchange.MessageExchangePattern.REQUEST_RESPONSE;
            if (isTwoWay) {
                // two way
                executionCallable = new HttpExternalService.TwoWayCallable(method, odeMex.getMessageExchangeId(), odeMex.getOperation());
                scheduler.registerSynchronizer(new Scheduler.Synchronizer() {
                    public void afterCompletion(boolean success) {
                        // If the TX is rolled back, then we don't send the request.
                        if (!success) return;
                        // The invocation must happen in a separate thread
                        executorService.submit(executionCallable);
                    }

                    public void beforeCompletion() {
                    }
                });
                odeMex.replyAsync();
            } else {
                // one way, just execute and forget
                executionCallable = new HttpExternalService.OneWayCallable(method, odeMex.getMessageExchangeId(), odeMex.getOperation());
                executorService.submit(executionCallable);
                odeMex.replyOneWayOk();
            }
        } catch (UnsupportedEncodingException e) {
            String errmsg = "Error sending message to Axis2 for ODE mex " + odeMex;
            log.error(errmsg, e);
            odeMex.replyWithFailure(MessageExchange.FailureType.FORMAT_ERROR, errmsg, null);
        }

    }


    private class OneWayCallable implements Callable<Void> {
        HttpMethod method;
        String mexId;
        Operation operation;

        public OneWayCallable(HttpMethod method, String mexId, Operation operation) {
            this.method = method;
            this.mexId = mexId;
            this.operation = operation;
        }

        public Void call() throws Exception {
            try {
                // simply execute the http method
                HttpClient client = new HttpClient(connections);
                if (log.isDebugEnabled())
                    log.debug("Executing http request : " + method.getName() + " " + method.getURI());
                final int statusCode = client.executeMethod(method);
                // ... and process the response
                processResponse(statusCode);
            } catch (final IOException e) {
                // ODE MEX needs to be invoked in a TX.
                try {
                    scheduler.execIsolatedTransaction(new Callable<Void>() {
                        public Void call() throws Exception {
                            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
                            String errmsg = "Unable to execute http request : " + e.getMessage();
                            log.error(errmsg, e);
                            odeMex.replyWithFailure(MessageExchange.FailureType.COMMUNICATION_ERROR, errmsg, null);
                            return null;
                        }
                    });
                } catch (Exception e1) {
                    String errmsg = "Error executing reply transaction; reply will be lost.";
                    log.error(errmsg, e);
                }
            } finally {
                method.releaseConnection();
            }
            return null;
        }

        public void processResponse(int statusCode) {
            // a one-way message does not care about the response
        }
    }

    private class TwoWayCallable extends OneWayCallable {
        public TwoWayCallable(HttpMethod method, String mexId, Operation operation) {
            super(method, mexId, operation);
        }

        public void processResponse(final int statusCode) {
            try {
                // ODE MEX needs to be invoked in a TX.
                scheduler.execIsolatedTransaction(new Callable<Void>() {
                    public Void call() throws Exception {

                        if (statusCode >= 200 && statusCode < 300) {
                            success();
                        } else if (statusCode >= 300 && statusCode < 400) {
                            redirection();
                        } else if (statusCode >= 400 && statusCode < 500) {
                            badRequest();
                        } else if (statusCode >= 500 && statusCode < 600) {
                            serverError();
                        } else {
                            unmanagedStatus();
                        }

                        return null;
                    }
                });
            } catch (Exception e) {
                String errmsg = "Error executing reply transaction; reply will be lost.";
                log.error(errmsg, e);
            }
        }

        private void unmanagedStatus() {
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            String errmsg = "Unmanaged Status Code! " + method.getStatusLine();
            log.error(errmsg);
            odeMex.replyWithFailure(MessageExchange.FailureType.OTHER, errmsg, null);
        }

        private void serverError() {
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            QName type = new QName(Namespaces.ODE_EXTENSION_NS, "HttpRemoteServerFault");

            Document odeMsg = DOMUtils.newDocument();
            Element odeMsgEl = odeMsg.createElementNS(null, "message");
            Element statusEl = odeMsg.createElementNS(null, "statusLine");
            statusEl.setTextContent(String.valueOf(method.getStatusLine()));

            odeMsg.appendChild(odeMsgEl);
            odeMsgEl.appendChild(statusEl);
            org.apache.ode.bpel.iapi.Message response = odeMex.createMessage(type);
            response.setMessage(odeMsgEl);

            log.error("Http Server Error! " + method.getStatusLine());
            odeMex.replyWithFault(type, response);
        }

        private void badRequest() {
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            String errmsg = "Bad Request! " + method.getStatusLine();
            log.error(errmsg);
            odeMex.replyWithFailure(MessageExchange.FailureType.OTHER, errmsg, null);
        }

        private void redirection() {
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            String errmsg = "Redirections are not supported! " + method.getStatusLine();
            log.error(errmsg);
            odeMex.replyWithFailure(MessageExchange.FailureType.OTHER, errmsg, null);
        }

        private void success() {
            if (log.isDebugEnabled()) log.debug("Http Status Line=" + method.getStatusLine());
            PartnerRoleMessageExchange odeMex = (PartnerRoleMessageExchange) server.getEngine().getMessageExchange(mexId);
            if (log.isDebugEnabled()) log.debug("Received response for MEX " + odeMex);
            try {
                final InputStream bodyAsStream = method.getResponseBodyAsStream();
                if (bodyAsStream == null) {
                    String errmsg = "Request body of a Two-way message may be empty! Msg Id=" + mexId;
                    log.error(errmsg);
                    odeMex.replyWithFailure(MessageExchange.FailureType.OTHER, errmsg, null);
                    return;
                } else {

                    // parse the body
                    Element bodyElement;
                    try {
                        bodyElement = DOMUtils.parse(bodyAsStream).getDocumentElement();
                    } catch (Exception e) {
                        String errmsg = "Unable to parse the request body : " + e.getMessage();
                        log.error(errmsg, e);
                        odeMex.replyWithFailure(MessageExchange.FailureType.FORMAT_ERROR, errmsg, null);
                        return;
                    }
                    try {
                        org.apache.ode.bpel.iapi.Message odeResponse = odeMex.createMessage(odeMex.getOperation().getOutput().getMessage().getQName());

                        // we expect a single part per output message
                        Part part = (Part) operation.getOutput().getMessage().getParts().values().iterator().next();

                        Element partElement = processBodyElement(part, bodyElement);
                        odeResponse.setPart(part.getName(), partElement);

                        if (log.isInfoEnabled())
                            log.info("Response:\n" + DOMUtils.domToString(odeResponse.getMessage()));
                        odeMex.reply(odeResponse);
                    } catch (Exception ex) {
                        String errmsg = "Unable to process response: " + ex.getMessage();
                        log.error(errmsg, ex);
                        odeMex.replyWithFailure(MessageExchange.FailureType.OTHER, errmsg, bodyElement);
                    }
                }
            } catch (IOException e) {
                String errmsg = "Unable to get the request body : " + e.getMessage();
                log.error(errmsg, e);
                odeMex.replyWithFailure(MessageExchange.FailureType.FORMAT_ERROR, errmsg, null);
                return;
            }
        }

        /**
         * Create the element to be inserted into org.apache.ode.bpel.iapi.Message.
         * If the part has a non-null element name, the bodyElement is simply appended.
         * Else if the bodyElement has a text content, the value is set to the message.
         * Else append all nodes of bodyElement to the returned element. Attributes are ignored.
         * <p/>
         * The name of the returned element is the part name.
         *
         * @param part
         * @param bodyElement
         * @return the element to insert "as is" to ODE message
         */
        private Element processBodyElement(Part part, Element bodyElement) {
            Document doc = DOMUtils.newDocument();
            Element partElement = doc.createElementNS(null, part.getName());
            if (part.getElementName() != null) {
                partElement.appendChild(bodyElement);
            } else {
                if (DOMUtils.isEmptyElement(bodyElement)) {
                    // Append an empty text node.
                    // Warning! setting an empty string with setTextContent has not effect. See javadoc.
                    partElement.appendChild(doc.createTextNode(""));
                } else {
                    String textContent = DOMUtils.getTextContent(bodyElement);
                    if (textContent != null) {
                        // this is a simple type
                        partElement.setTextContent(textContent);
                    } else {
                        // this is a complex type, import every child
                        // !!! Attributes are ignored
                        for (int m = 0; m < bodyElement.getChildNodes().getLength(); m++) {
                            Node child = bodyElement.getChildNodes().item(m);
                            partElement.appendChild(doc.importNode(child, true));
                        }
                        if (bodyElement.hasAttributes()) {
                            if (log.isWarnEnabled())
                                log.warn("Element received for output part " + part.getName() + " has attributes that won't be imported into ODE.");
                        }
                    }
                }

            }
            return partElement;
        }

    }
}