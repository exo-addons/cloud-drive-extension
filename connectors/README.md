eXo Cloud Drive Connectors
==========================

Cloud Drive add-on offers integration of cloud documents in eXo Platform via connectors. Add-on has extensible Connector API that allows develop connectors to new cloud providers withour changing the core code.
The add-on it is a portal extension to Platform. And each connector it is also a portal extension that depends on the add-on core extension.

Currently existing connectors embedded in Cloud Drive add-on package:
* Google Drive
* Box
* Any CMIS compliant repository

Below it is described how to configure existing connectors. For development of a new connector refer to [Connector API documentation](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md).

Getting started
===============

Cloud Drive connector implements initial connectivity and synchronization between local drive in Documents app and remote cloud documents. Connector acts as a bridge between cloud provider specifics and Cloud Drive abstraction in eXo Platform. As a result each connector may need configuration of such aspects as local storage (e.g. JCR namespaces and nodetypes), internal identifiers, authentication and/or authorization credentials, networking etc. All these properties can be split on general, common for all conectors, and specific for only one, or several, of them.

Configuration
-------------

Cloud Drive connector configuration it is a component plugin of `CloudDriveService`. Embedded connectors already configured in their extensions with mappings to runtime properties, and thus need only modifications in `configuration.properties` of the Platform server. Third-party connectors may need a component plugin configuration if it is not already provied by the connector. Refer to the connector documentation to find required settings.

Here is a configuration of properties that can be done in `configuration.properties` of the Platform. For a connector plugin configuration refer to [development documentation](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md).

Common properties for all connectors are:
* `clouddrive.service.host` - a host name what will be used for OAuth2 redirect URL compilation
* `clouddrive.service.schema` - optional settings to force use of non HTTP protocol for authorization and redirect URLs, default value is `http`
* `clouddrive.login.sso` - optional, if set to `true` and connector supports special logic for Single-Sign-On, it will use that logic. SSO may require additional setting specific for its cloud provider.

Example: 

    # Force use of HTTPS for authorisation URLs
    clouddrive.service.schema=https
    # This host name myplatform.com will be used in auth and redirect URLs
    clouddrive.service.host=myplatform.com

Another group of properties also common for all connectors but differ in names for different providers:
* `clouddrive.PROVIDER_ID.client.id` - client ID for OAuth2 authorization
* `clouddrive.PROVIDER_ID.client.secret` - OAuth2 secret for given client 

Where `PROVIDER_ID` should be replaced by actual provider ID as described below.

Google Drive
------------

Provider ID: **gdrive**.
This connector ~~supports both in HTTP and HTTPS~~ schemes. 

Google Drive connector sample configuration:

    clouddrive.google.client.id=00000000000@developer.gserviceaccount.com
    clouddrive.google.client.secret=XXXXXXX

Box
---

Provider ID: **box**.
This connector supports ~~only work in HTTPS~~ scheme. You cannot use Box in production if don't run your Platform via secure HTTP connection. Thus `clouddrive.service.schema=https` is mandatory for Box connector.  

Box connector sample configuration:

    clouddrive.service.schema=https
    clouddrive.service.host=mysecureplatform.com
    clouddrive.box.client.id=YYYYYY
    clouddrive.box.client.secret=ZZZZZZ

To force Box SSO for connection authorization and for embedded file views it's possible to enable SSO via configuration. But it will also require Box specific configuration. There are two specific for SSO options in Box connector:

* a partner SAML Identity Provider ID, this ID will be used to construct SSO URL

    `clouddrive.box.sso.partneridpid=YOUR_PARTNER_ID`

* or need provider a ready SSO URL

    `clouddrive.box.sso.url=CUSTOM_SSO_URL`

Ready SSO URL has precedence on partner ID, if exists it will be used to construct OAuth2 URL by appending actual authentication URL at the end. Take this in account when configuring SSO URL.

When provide partner ID, then Box connector will construct SSO URL in following form:
`https://sso.services.box.net/sp/startSSO.ping?PartnerIdpId=${clouddrive.box.sso.partneridpid}&TargetResource=${OAUTH2_URL}`. Where `OAUTH2_URL` an authentication URL as described in [Box documentation](https://developers.box.com/oauth/).

Box connector sample configuration with SSO support (via provider ID):

    clouddrive.service.schema=https
    clouddrive.service.host=mysecureplatform.com
    clouddrive.box.client.id=YYYYYY
    clouddrive.box.client.secret=ZZZZZZ
    clouddrive.box.sso.partneridpid=RRRRRRR

CMIS
----

CMIS connector doesn't require any configuration for getting started. But you may find useful to predefine your CMIS servers for better user experience. When user try connect CMIS repository as a cloud drive, the add-on will show a form where need fill server URL and username with password. If several repositories found on the server, then user will need to choose one of them. Predefined services allow set list of named URLs in the form available from dropdown menu of _Service URL_ field. Find more on CMIS connector [page](https://github.com/exo-addons/cloud-drive-extension/blob/master/connectors/cmis/README.md). 


Deployment
==========

Cloud Drive extension package already contains all embedded connectors enabled (listed above). When the add-on installed to eXo Platform server they will be installed also. Then in runtime they will be plugged to the Cloud Drive service component and appear in Document app menu. 

Third-party connectors, indeed, need a dedicated deployment to become available in Platform. Each such connector it is a portal extension itself with own configuration. Its installation and removal can be easily managed by Platform Add-ons Manager. What may be required to do manually after that, it's add related properties to _configuration.properties_ file. Follow the connector setup guide for the configuration. 

If need disable one of embedded connectors, need remove its extension files from the Platform server. But take in account that connectors installed as part of Cloud Drive add-on, they are not separate portal extensions and cannot be removed by Platform Add-ons Manager (or it's predecessor Extension manager script). Connector files should be removed manually from server's folders (_lib_, _webapp_ etc.).

Template connector
==================

It is not a connector that can be used in production, it is a template for development of new connectors. See [Connector API documentation](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md) for further details.











