eXo Cloud Drive extension
=========================

eXo Platform portal extension providing integration with cloud drives such as Google Drive.
Users must have a valid Google account to connect Google Drive.

Thanks to this extension it's possible connect cloud drives as folders in Documents app and then explose user files using features of WCM Document Explorer.

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

By default, Cloud Drive assumes that it runs on non-secure host (http protocol). But Box API requires secure URI for a production, thus it needs https URL for OAuth2 redirect and you have to configure your production to support SSL HTTP. You also may use your Platform server with enabled SSL connector for other needs. In both cases you need add `clouddrive.service.schema` to the configuration with proper value "https".


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

Since version 1.1.0-Beta5 there is Feature API in Cloud Drive extension. It is Java and REST services that can be used to control new drive creation and automatic synchronization on per user basis. Thanks to this API, it's possible to restrict a connection to a new cloud accounts if an user has limitations in the Platform (resources quota, limited permissions etc); the same rule can be applied to a new auto-synchrinization that is working when an user in the drive folder in Documents.

Technical details of in [Features API documentation](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/FEATURES_API.md).











