eXo Cloud Drive add-on
======================

eXo Cloud Drive it is a portal extension to eXo Platform which is providing integration with remote cloud documents such as Google Drive or Box. Users must have a valid cloud account to connect his documents.

Thanks to this extension it's possible connect cloud drives as folders in eXo Documents and then access user files using features of ECMS Document Explorer.

Currently supported cloud drives:
* Google Drive
* Box

This addon is for eXo Platform version 4.0.

Getting started
===============

Cloud Drive add-on binaries can be downloaded from [eXo Add-ons on SourceForge](http://sourceforge.net/projects/exo/files/Addons/Cloud%20Drive/) or build from sources. 


Build from sources
------------------

To build add-on from sources use [Maven 3](http://maven.apache.org/download.html).

Clone the project with:

    git clone git@github.com:exo-addons/cloud-drive-extension.git
    cd cloud-drive-extension

Build it with

    mvn clean package
    
Go to packaging bundle file created by last build in `cloud-drive-extension/packaging/target/cloud-drive-bundle-packaging.zip`. Use it for deployment to Platform below.


Deploy to eXo Platform
----------------------

Install [eXo Platform 4.0 Tomcat bundle](http://learn.exoplatform.com/Download-eXo-Platform-Express-Edition-En.html) to some directory, e.g. `/opt/platform-tomcat`.

Extract the add-on bundle archive to `extensions` subfolder in the Platform folder.

```
unzip ./cloud-drive-bundle-packaging.zip -d /opt/platform-tomcat/clouddrive
```

Install the add-on extension from root of the Platform:

```
./extension.sh --install clouddrive
```

This will copy required files to the Platform Tomcat folders, details will print to the console.

Enable Google Drive API
-----------------------

- Go to the Google API Console : https://code.google.com/apis/console/
- Create an new API project
- In the Services page, enable the Drive API

![Google Drive API](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/readme/google-drive-api.png)

- In the API Access page, click on the "Create an OAuth 2.0 client ID..." button
- Fill the form with a product name of your choice (e.g. "My Platform"), an optionnally a product logo and a home page URL
- Click Next
- Select the "Web application account" option
- Click more options on "Your site or hostname", later assumed http://myplatform.com as host name of the server
- Enter "Authorized Redirect URIs": http://myplatform.com/portal/rest/clouddrive/connect/gdrive. Note that path in the URI should exactly "/portal/rest/clouddrive/connect/gdrive".
- "Authorized JavaScript Origins": http://myplatform.com
- Click on "Create client ID"
- Remember `Client ID` and `Client secret` for configuration below.

![Google Drive API Access](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/readme/google-drive-access.png)

Enable Box API
--------------

- Go to Box Developers site, to [My Box Apps](http://box.com/developers/services).
- Create a new app with API Key Type: Content API. This action will warn you that it will upgrade your account to a Development type with an access to Enterprise features. Take this in account, you may consider for a dedicated Box account to manage your keys to Box API. Details about OAuth2 access described in [this guide](http://developers.box.com/oauth/). Note: don't need point `redirect_uri` for the app, it will be submited by the add-on in the authentication requests.
- Use your `client_id` and `client_secret` values for configuration below.

Configuration
-------------

Open the configuration file of your Platform server `/opt/platform-tomcat/gatein/conf/configuration.properties`

Add the two following variables :

    #clouddrive.service.schema=https
    #clouddrive.service.host=mysecureplatform.com
    clouddrive.service.host=myplatform.com
    clouddrive.google.client.id=00000000000@developer.gserviceaccount.com
    clouddrive.google.client.secret=XXXXXXX
    clouddrive.box.client.id=YYYYYY
    clouddrive.box.client.secret=ZZZZZZ

The `clouddrive.google.client.id` parameter is the `Client ID` of the service account (available in your Google console, see previous screenshot).
The `clouddrive.google.client.secret` parameter is `Client Secret` of the service account (available in your Google console, see above).
The same way `clouddrive.box.client.id` and `clouddrive.box.client.secret` refer to Box's `client_id` and `client_secret`.

By default, Cloud Drive assumes that it runs on non-secure host (http protocol). But Box API requires secure URI for a production, thus it needs https URL for OAuth2 redirect and you have to configure your production to support SSL HTTP. You also may use your Platform server with enabled HTTPS connector for other needs. In both cases you need add `clouddrive.service.schema` to the configuration with proper value "https".

For more details check [configuration section](https://raw.github.com/exo-addons/cloud-drive-extension/master/connectors/README.md) on connectors page. 

Single Sign-On support
----------------------

Single Sign-On (SSO) often used by enterprise and they may adapt SSO to access their cloud files. Cloud Drive add-on uses OAuth2 URL that cloud provider offers, it offten can be enough to leverage the SSO available for your enterprise (e.g. for Google Drive). But other cloud providers (e.g. Box) may require an another URL to force SSO for user login. To be able solve this you can force use of SSO via configuration in the add-on.

To enable SSO in configuration add following parameter:

    clouddrive.login.sso=true

This will tell a drive connector to force SSO for authentication URL (to obtain OAuth2 tokens or for embedded file view). But a drive connector may require additional parameters to enable SSO. They are provider specific. Below specific configiration described for Box.

There are two options for Box connector:

- Need provide a partner SAML Identity Provider ID, this ID will be used to construct SSO URL:

    `clouddrive.box.sso.partneridpid=YOUR_PARTNER_ID`
    
- Or provider ready SSO URL

    `clouddrive.box.sso.url=CUSTOM_SSO_URL`

Ready SSO URL has precedence on partner ID, if exists it will be used to construct OAuth2 URL by appending actual authentication URL at the end. Take this in account when configuring SSO URL.

When provide partner ID, then Box connector will construct SSO URL in following form:
`https://sso.services.box.net/sp/startSSO.ping?PartnerIdpId=${clouddrive.box.sso.partneridpid}&TargetResource=${OAUTH2_URL}`. Where `OAUTH2_URL` an authentication URL as described in [Box documentation](https://developers.box.com/oauth/).

Run Platform
------------

Switch to a folder with your Platform and start it.

    cd /opt/platform-tomcat
    ./start_eXo.sh
    

Use Cloud Drive extension
=========================

In running Platform go to Documents app, open Personal Documents folder root and click "Connect your Google Drive".
Detailed steps described in this post [eXo Add-on in Action: Connecting your Google Drive to eXo Platform](http://blog.exoplatform.com/2013/02/28/exo-add-on-in-action-connecting-your-google-drive-to-exo-platform).

Features Management
===================

Since version 1.1.0-Beta5 there is Feature API in Cloud Drive extension. It is Java and REST services that can be used to control new drive creation and automatic synchronization on per user basis. Thanks to this API, it's possible to restrict a connection to a new cloud accounts if an user has limitations in the Platform (resources quota, limited permissions etc); the same rule can be applied to a new auto-synchronization that is working when an user in the drive folder in Documents.

Technical details in [Features API documentation](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/FEATURES_API.md).

Create new connectors
=====================

Cloud Drive add-on is extensible and it's possible to create new connectors to support more cloud providers. Since version 1.1.0-RC2 internal architecture reorganized to offer better separation based on conventions and allow client modules in Javascript. 

Follow [Connector API](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md) for understanding of development conventions and for required steps.










