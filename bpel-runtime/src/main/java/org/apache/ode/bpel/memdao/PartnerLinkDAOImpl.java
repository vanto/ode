package org.apache.ode.bpel.memdao;

import org.apache.ode.bpel.dao.PartnerLinkDAO;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;

/**
 * A very simple, in-memory implementation of the {@link org.apache.ode.bpel.dao.PartnerLinkDAO} interface.
 */
public class PartnerLinkDAOImpl extends DaoBaseImpl implements PartnerLinkDAO {

  private int _modelId;
  private String _linkName;
  private String _myRoleName;
  private QName _myRoleServiceName;
  private String _partnerRoleName;
  private Element _myEpr;
  private Element _partnerEpr;

  public String getPartnerLinkName() {
    return _linkName;
  }

  public void setPartnerLinkName(String partnerLinkName) {
    _linkName = partnerLinkName;
  }

  public int getPartnerLinkModelId() {
    return _modelId;
  }

  public void setPartnerLinkModelId(int modelId) {
    _modelId = modelId;
  }

  public String getMyRoleName() {
    return _myRoleName;
  }

  public QName getMyRoleServiceName() {
    return _myRoleServiceName;
  }

  public void setMyRoleServiceName(QName svcName) {
    _myRoleServiceName = svcName;
  }

  public void setMyRoleName(String myRoleName) {
    _myRoleName = myRoleName;
  }

  public String getPartnerRoleName() {
    return _partnerRoleName;
  }

  public void setPartnerRoleName(String partnerRoleName) {
    _partnerRoleName = partnerRoleName;
  }

  public Element getMyEPR() {
    return _myEpr;
  }

  public void setMyEPR(Element myEpr) {
    _myEpr = myEpr;
  }

  public Element getPartnerEPR() {
    return _partnerEpr;
  }

  public void setPartnerEPR(Element partnerEpr) {
    _partnerEpr = partnerEpr;
  }

}
