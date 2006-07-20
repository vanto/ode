package com.fs.pxe.axis2.hooks;


import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.AxisOperation;
import org.apache.axis2.description.AxisService;
import org.apache.axis2.description.HandlerDescription;
import org.apache.axis2.engine.AbstractDispatcher;
import org.apache.axis2.engine.AxisConfiguration;
import org.apache.axis2.i18n.Messages;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.xml.namespace.QName;


/**
 * Dispatches the service based on the information from the target endpoint URL.
 */
public class PXEAxisDispatcher extends AbstractDispatcher {

    /**
     * Field NAME
     */
    public static final QName NAME = new QName("http://pxe.intalio.org/",
            "PXEAxisDispatcher");
    private static final Log log = LogFactory.getLog(PXEAxisDispatcher.class);
    QName operationName = null;


    public AxisOperation findOperation(AxisService service, MessageContext messageContext)
            throws AxisFault {
        AxisOperation operation;

        // Start with the wsaAction. We assume wsaAction is the more reliable
        // way to identify the operation.
        String action = messageContext.getWSAAction();
        if (action != null) {
            log.debug(Messages.getMessage("checkingoperation", action));
            operation = service.getOperationByAction(action);
            if (operation != null)
                return operation;
        }

        // Failing that, look at the body of the SOAP message. We expect one
        // element that has the same (local) name as the operation. This works
        // well for RPC, not always for Doc/Lit.
        OMElement bodyFirstChild = messageContext.getEnvelope().getBody().getFirstElement();
        if (bodyFirstChild != null) {
            String localName = bodyFirstChild.getLocalName();
            log.debug("Checking for Operation using SOAP message body's first child's local name : "
                            + localName);
            operation = service.getOperation(new QName(localName));
            if (operation != null) {
                log.debug("Found operation " + operation);
                return operation;
            }

            // Of course, the element name most likely uses the suffix
            // Request or Response, so look for those and strip them.
            int index = localName.lastIndexOf("Request");
            if (index >=0 && index + "Request".length() == localName.length()) {
                return service.getOperation(new QName(localName.substring(0, index)));
            }
            index = localName.lastIndexOf("Response");
            if (index >=0 && index + "Response".length() == localName.length()) {
                return service.getOperation(new QName(localName.substring(0, index)));
            }
        }
        log.warn("No operation has been found!");
        return null;
    }

    /*
     *  (non-Javadoc)
     * @see org.apache.axis2.engine.AbstractDispatcher#findService(org.apache.axis2.context.MessageContext)
     */
    public AxisService findService(MessageContext messageContext) throws AxisFault {
        EndpointReference toEPR = messageContext.getTo();

        if (toEPR != null) {
            log.debug("Checking for Service using target endpoint address : " + toEPR.getAddress());

            // The only thing we understand if a service name that
            // follows /processes/ in the request URL.
            String path = parseRequestURLForService(toEPR.getAddress());
            if (path != null) {
                AxisConfiguration registry =
                        messageContext.getConfigurationContext().getAxisConfiguration();
                AxisService service = registry.getService(path);
                log.debug("Found service in registry from name " + path + ": " + service);
                return service;
            }
        }
        log.warn("No service has been found!");
        return null;
    }
    

    public void initDispatcher() {
        init(new HandlerDescription(NAME));
    }

    /**
     * Obtain the service name from the request URL. The request URL is
     * expected to use the path "/processes/" under which all processes
     * and their services are listed. Returns null if the path does not
     * contain this part.
     */
    protected String parseRequestURLForService(String path) {
        int index = path.indexOf("/processes/");
        if (-1 != index) {
            String service;

            int serviceStart = index + "/processes/".length();
            if (path.length() > serviceStart + 1) {
                service = path.substring(serviceStart);
                // Path may contain query string, not interesting for us.
                int queryIndex = service.indexOf('?');
                if (queryIndex > 0) {
                    service = service.substring(0, queryIndex);
                }
                return service;
            }
        }
        return null;
    }

}
