eXo Cloud Drive add-on
======================

eXo Cloud Drive it is a portal extension to eXo Platform which is providing integration with remote cloud documents such as Google Drive or Box. Users must have a valid cloud account to connect his documents.

Thanks to this extension it's possible connect cloud drives as folders in eXo Documents and then access user files using features of ECMS Document Explorer.

Currently supported cloud drives:
* Google Drive
* Dropbox
* Box
* Any CMIS compliant repository

This addon is for eXo Platform version 4.0 and higher.

Getting started
===============

Cloud Drive add-on can be installed from Add-ons catalog. Older binaries can be downloaded from [eXo Add-ons on SourceForge](http://sourceforge.net/projects/exo/files/Addons/Cloud%20Drive/) or build from sources. 


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

Install [eXo Platform Tomcat bundle](http://learn.exoplatform.com/Download-eXo-Platform-Express-Edition-En.html) to some directory, e.g. `/opt/platform-tomcat`.

Users of Platform 4.1 and higher, and those who installed [Addons Manager](https://github.com/exoplatform/addons-manager) in Platform 4.0, can simple install the add-on from central catalog by command:

```
./addon install exo-cloud-drive
```

If want install latest development milestone (beta or RC) use "--unstable" option with the _addon_ tool. To install current development version (daily build from our CI) use "--snapshots" option.

Users of base Platform 4.0 need [download](http://sourceforge.net/projects/exo/files/Addons/Cloud%20Drive/) the add-on bundle and extract it to `extensions` subfolder in the Platform folder.

```
unzip ./cloud-drive-bundle-packaging.zip -d /opt/platform-tomcat/clouddrive
```

Install the add-on extension from root of the Platform:

```
./extension.sh --install clouddrive
```

This will copy required files to the Platform Tomcat folders, details will be printed to the console.


Enable Google Drive API
-----------------------

- Go to the Google API Console : https://code.google.com/apis/console/
- Create an new API project
- In the APIs page, enable the Drive API

![Google Drive API](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/readme/google-drive-api.png)

- You need OAuth consent screen for you app, if not yet already created - then fill this tab in API Credentials page: product name, homepage (below assumed as http://myplatform.com), logo, terms.
- In the API Credentials tab, add credentials of type "OAuth 2.0 client ID"
- Choose "Web application" type for your client ID 
- Enter a name of the client, e.g. "My Platform Web Client"
- Enter "Authorized Redirect URIs", e.g. http://myplatform.com/portal/rest/clouddrive/connect/gdrive. Note that path in the URI should be exactly  "/portal/rest/clouddrive/connect/gdrive".
- Provide "Authorized JavaScript Origins": http://myplatform.com
- Click "Create" button
- Remember `Client ID` and `Client secret` for configuration below.

![Google Drive API Access](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/readme/google-drive-access.png)

- Sample configuration of Google Drive connector by the [link](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors#google-drive)

Enable Dropbox API
------------------

- Go to Dropbox App Console : https://www.dropbox.com/developers/apps
- Create a new Dropbox Platform app of type "Dropbox API app"
- This app should have an access to all files already on Dropbox
- The app needs access to all file types - a user's full Dropbox
- Provide a name of your app and submit to create the app
- Your app will be created with Development status and for development you'll need link user sits to the app (max 100 users). Later you can apply for production.
- Add redirect URIs for you app : https://myplatform.com/portal/rest/clouddrive/connect/dropbox. Important that the path in the URI should end exactly with "/portal/rest/clouddrive/connect/dropbox". URI should be secure (HTTPS). 
- For development purpose you also can add redirect URI to a server on localhost, it can be non-HTTPS.

![Dropbox App Settings](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/readme/dropbox-app-settings.png)

- Leave all other fileds as is: Cloud Drive doesn't require implicit grant, thus disallow it for the app; it doesn't need a pre-generated access token and doesn't use Webhooks
- Sample configuration of Dropbox connector by the [link](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors#dropbox)

Enable Box API
--------------

- Go to Box Developers site, to [My Applications](https://app.box.com/developers/services).
- Create a new app of type Box Content. This action will warn you that it will upgrade your account to a Development type with an access to Enterprise features. Take this in account, you may consider for a dedicated Box account to manage your keys to Box API. Details about OAuth2 access described in [this guide](http://developers.box.com/oauth/). Note: when you create a new app it will not ask for `redirect_uri`, but later if you will try to save the app it will be required - the URI path should end exactly with "/portal/rest/clouddrive/connect/box", e.g. https://myplatform.com/portal/rest/clouddrive/connect/box. URI should be secure (HTTPS) but for development purpose servers on localhost also possible. 
- Use your `client_id` and `client_secret` values for configuration below.

![Box API Access](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/readme/box-access.png)

- Sample configuration of Box connector by the [link](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors#box)
- You also can use Box SSO, see instructions provided below.

CMIS repositories
-----------------

To connect CMIS repository you need following: 
- an URL of AtomPub binding of your CMIS server
- username and password to authenticate to the server
- if the the server has several repositories you'll need to select an one: each repository can be connected as a separate drive

Important notice that username and password will be sent in plain text, thus enasure you are connecting via secure connection in production (e.g. via HTTPS). 

More information find on [CMIS connector page](https://github.com/exo-addons/cloud-drive-extension/blob/master/connectors/cmis/README.md).

If you need connect Microsoft SharePoint in eXo Platform, use our [dedicated add-on](https://github.com/exo-addons/cloud-drive-sharepoint) based on the Cloud Drive CMIS connector.

Configuration
-------------

Open the configuration file of your Platform server `/opt/platform-tomcat/gatein/conf/exo.properties` (`configuration.properties` for Platform 4.0).

Add the two following variables:

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

By default, Cloud Drive assumes that it runs on non-secure host (http protocol). But Dropbox and Box APIes require secure URI for a production, thus they needs HTTPS URL for OAuth2 redirect and you have to configure your production to support HTTP over SSL. You also may use your Platform server with enabled HTTPS connector for other needs. In both cases you need add `clouddrive.service.schema` to the configuration with proper value "https".

For more details check [configuration section](https://github.com/exo-addons/cloud-drive-extension/blob/master/connectors/README.md) on connectors page. 

CMIS connector has additional optional settings to [configure](https://github.com/exo-addons/cloud-drive-extension/blob/master/connectors/cmis/README.md).

Enable and disable connectors
-----------------------------

Cloud Drive allows you to manage which connectors from available to use in your server. There are several levels where connectors can be removed or disabled. 

Since Cloud Drive version 1.3.1 and all 1.4, it's possible to disable a connector by configuration:
* in _exo.properties_ by property in format clouddrive.PROVIDER\_ID.disable where PROVIDER\_ID an actual ID of your connector, e.g. to disable CMIS you need the following
  
    `clouddrive.cmis.disable=true`
  
* use `CloudDriveService` component plugin to remove the connector, it is similar to addition configuration: provider's ID and name should be the same as for the added
    ```xml
    <!-- CMIS connector removal -->
    <external-component-plugins>
      <target-component>org.exoplatform.clouddrive.CloudDriveService</target-component>
      <component-plugin>
        <name>remove.clouddriveprovider</name>
        <set-method>removePlugin</set-method>
        <type>org.exoplatform.clouddrive.cmis.CMISConnector</type>
        <init-params>
          <properties-param>
            <name>drive-configuration</name>
            <property name="provider-id" value="cmis" />
            <property name="provider-name" value="CMIS" />
            <property name="provider-client-id" value="" />
            <property name="provider-client-secret" value="" />
          </properties-param>
        </init-params>
      </component-plugin>
    </external-component-plugins>
    ```

When you use Cloud Drive from own extension or custom Platform build, then you also can exclude a connector artifacts (JAR and WAR) from the packaging - then Cloud Drive core will not load them at all. If packaging approach not possible then you can use XML configuration of `CloudDriveService` as described above.


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

In running Platform go to Documents app, open Personal Documents folder root and click "Connect Cloud Document". There are also menu actions dedicated for each registred connector, e.g. for Google Drive it will be "Connect your Google Drive". You can enable them via Content Administration menu, in Explorer, Views section go edit the view and edit its action.
Detailed steps you can find in this post [Access your Box.com documents using this great Cloud Drive Add-on](https://www.exoplatform.com/blog/en/2014/01/15/access-box-com-documents-great-cloud-drive-add).

Troubleshooting
---------------

Cloud Drive works between two storages: local Platform repository (JCR) and remote cloud services. In most cases the Cloud Drive able to fix the problems related to connectivity or eventual inconsistency. But rare cases also may have a place: disk or power failures that lead to server crash or remote cloud temporal errors. They may create data inconsistency that will need a human action. When an error happen you will see a red popup with short details, often it will ask to retry an operation later. In most of cases it will be enough to refresh the Documents portlet (use Refresh icon), but if it doesn't help then need reload the browser page.

In some cases it may be required to remove the connected drive folder and connect it again. This can be required if error cannot be fixed by refreshing the explorer. By drive folder removal need understand only removal of local representation of the drive in eXo Platform - nothing will be deleted actually in your cloud documents. 

Should you care about your local data possibly not saved remotelly? It's depeends on an operation that caused the not recoverable error. If you just uploaded a document via eXo and its synchronization failed, you'll see that your file's name have gray color (semi-transparent), it also may have "Push to..." menu action when selected in the documents explorer. In this case it's recommended to copy your document to some folder outside the local drive folder, e.g. to _Documents_ in root of your Personal Documents. And only then do remove the drive folder. After connecting it again, you will be able to move your document to the drive to upload it remotelly.

When struggling with unrecoverable error in your drive it's also may help to ask your Platform administrator to check the logs. There can be technical details that will help understand the problem and how better to fix it.


Developing with Cloud Drive
===========================

There is a single entry point to the Cloud Drive API: _CloudDriveService_ component, you can get from eXo container. When you need interact with cloud drives (connect, find etc.), you need use this component only.

Cloud Drive consists of core and ECMS services and extension webapp. The core offers common logic implementation for connecting, synchronizing and storing remote files in JCR. These common abstractions can be adapted to many external file storages and cloud services. To make the architecture pluggable Cloud Drive introduced [Connector API](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md), see a paragraph below about creation of new connectors for further details. All connectors, are component plugins of _CloudDriveService_ and don't need get/create/invoke them explicitly to work with particular type of cloud drive, this component will do this for you. 

When you work from outside the Platform's JVM, you may use existing RESTful services: _ConnectService_, _DriveService_, _ProviderService_, _FeaturesService_. Refer to javadoc of these classes for usage interfaces.

There is also a Javascript client which can be loaded as AMD module (via RequireJS). It offers UI support for ECMS views and collection of helpful methods to access Cloud Drive web-services. This client described more in [Connector API](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md).

Having _CloudDriveService_ components in the hands you can use it to get available providers and proceed with a flow to connect your remote drive:
* obtain instance of cloud provider via _getProvider(String id)_ with required connector id.
* authenticate your user _authenticate(CloudProvider cloudProvider, String key)_, this method historically build for OAuth2 flow and assume that you already have a _key_ - an authorization code from your OAuth2 service. It also assumes that related connectors already configured with required client credentials.
* having cloud user instance you can connect remote drive to any JCR node (it should be _nt:folder_). The add-on doesn't care about what is it a node and where it located. Limitation to Personal Documents placed on WebUI level via component filter _PersonalDocumentsFilter_ for action components in ECMS UI. You can choose for a node from your requirements. Use method _createDrive(CloudUser user, Node driveNode)_ to create cloud drive in this node. The add-on will use it as a root of the remote drive and will manage its content respectively. Under drive creation it assumes initial fetch of all remote files and creation of meta-objects as sub-nodes in the JCR.
* if you need find/test if some node already is a connected cloud drive - use _findDrive()_ methods for this purpose.
* how synchronization works: it should be invoked from outside the add-on via a method on drive instance _CloudDrive.synchronize()_. Cloud Drive integration with ECMS UI does this automatically thanks to Javascript client, loaded in ECMS pages as part of the add-on WebUI components and managed by set of filters (_CloudDriveFilter_, _CloudFileFilter_, _BelongToCloudDriveFilter_). When you open your node in ECMS file explorer they should work for you and you don't need anything to invoke the synchronization. For other pages you'll need use Javascript client to invoke synchronization according your app logic.

Below a sample code to connect Google Drive to some JCR node you prepared:
```java
// Your JCR node of type nt:folder, it will be a root folder of cloud drive in eXo
Node node = ...;
// get eXo container
ExoContainer myContainer = ExoContainerContext.getCurrentContainer(); 
// obtain OAuth2 authentication code in your app
String code = ...; 
// use CloudDriveService 
CloudDriveService cloudDrives = (CloudDriveService) myContainer.getComponentInstance(CloudDriveService.class); 
CloudProvider googleProvider = cloudDrives.getProvider("gdrive"); 
CloudUser googleUser = cloudDrives.authenticate(googleProvider, code);
CloudDrive myGoogleDrive = cloudDrives.createDrive(googleUser, node); 
// you may store myGoogleDrive instance for later use, e.g. add listeners, get its files or invoke synchronization explicitly
```

Features Management
===================

Since version 1.1.0-Beta5 there is Feature API in Cloud Drive extension. It is Java and REST services that can be used to control new drive creation and automatic synchronization on per user basis. Thanks to this API, it's possible to restrict a connection to a new cloud accounts if an user has limitations in the Platform (resources quota, limited permissions etc); the same rule can be applied to a new auto-synchronization that is working when an user in the drive folder in Documents.

Technical details in [Features API documentation](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/FEATURES_API.md).

Create new connectors
=====================

Cloud Drive add-on is extensible and it's possible to create new connectors to support more cloud providers. Since version 1.1.0-RC2 internal architecture reorganized to offer better separation based on conventions and allow client modules in Javascript. 

Follow [Connector API](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md) for understanding of development conventions and for required steps.










