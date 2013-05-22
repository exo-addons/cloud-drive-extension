eXo Cloud Drive extension
=========================

eXo Platform 3.5 portal extension providing integration with cloud drives such as Google Drive.
Users must have a valid Google account to connect Google Drive.

Thanks to this extension it's possible connect cloud drives as folders in Documents app and then explose user files using features of WCM Document Explorer.

Currently supported cloud drives:
* Google Drive

This adddon is for eXo Platform version 3.5.

Getting started
===============

Build from sources
------------------

To build addon from sources use [Maven 3](http://maven.apache.org/download.html).

Clone the project with:

    git clone git@github.com:exo-addons/cloud-drive-extension.git
    cd cloud-drive-extension

Build it with

    mvn clean package

Deploy to Platform 3.5
----------------------

Install [eXo Platform 3.5 Tomcat bundle](http://www.exoplatform.com/company/en/download-exo-platform) to some directory, e.g. /opt/platform-tomcat.

Go to source directory of Cloud Drive, find packaging bundle file created by last build cloud-drive-extension/packaging/target/cloud-drive-bundle-packaging.zip. Extract this archive to your platform folder.

```
unzip ./cloud-drive-bundle-packaging.zip -d /opt/platform-tomcat
```

This will copy following files to Platform Tomcat folders:
```
[root@localhost target]# unzip ./cloud-drive-bundle-packaging.zip -d /opt/platform-tomcat
Archive:  ./cloud-drive-bundle-packaging.zip
   creating: /opt/platform-tomcat/conf/
   creating: /opt/platform-tomcat/conf/Catalina/
   creating: /opt/platform-tomcat/conf/Catalina/localhost/
  inflating: /opt/platform-tomcat/conf/Catalina/localhost/cloud-drive.xml  
   creating: /opt/platform-tomcat/webapps/
 extracting: /opt/platform-tomcat/webapps/cloud-drive.war  
   creating: /opt/platform-tomcat/lib/
 extracting: /opt/platform-tomcat/lib/exo-clouddrive-services-core-1.0-SNAPSHOT.jar  
 extracting: /opt/platform-tomcat/lib/google-api-client-1.10.3-beta.jar  
 extracting: /opt/platform-tomcat/lib/google-oauth-client-1.10.1-beta.jar  
 extracting: /opt/platform-tomcat/lib/google-http-client-1.10.3-beta.jar  
 extracting: /opt/platform-tomcat/lib/jsr305-1.3.9.jar  
 extracting: /opt/platform-tomcat/lib/gson-2.1.jar  
 extracting: /opt/platform-tomcat/lib/guava-11.0.1.jar  
 extracting: /opt/platform-tomcat/lib/jackson-core-asl-1.9.4.jar  
 extracting: /opt/platform-tomcat/lib/protobuf-java-2.2.0.jar  
 extracting: /opt/platform-tomcat/lib/google-api-services-drive-v2-rev1-1.7.2-beta.jar  
 extracting: /opt/platform-tomcat/lib/google-api-services-oauth2-v2-rev9-1.7.2-beta.jar  
 extracting: /opt/platform-tomcat/lib/exo-clouddrive-services-ecms-1.0-SNAPSHOT.jar
```

Enable Google Drive API
-----------------------

- Go to the Google API Console : https://code.google.com/apis/console/
- Create an new API project
- In the Services page, enable the Drive API

![Google Drive API](https://raw.github.com/exo-addons/cloud-drive-extension/master/readme-resources/google-drive-api.png)

- In the API Access page, click on the "Create an OAuth 2.0 client ID..." button
- Fill the form with a product name of your choice (e.g. "My Platform"), an optionnally a product logo and a home page URL
- Click Next
- Select the "Web application account" option
- Click more options on "Your site or hostname", later assumed http://myplatform.com as host name of the server
- Enter "Authorized Redirect URIs": http://myplatform.com/portal/rest/clouddrive/connect/gdrive. Note that path in the URI should exactly "/portal/rest/clouddrive/connect/gdrive".
- "Authorized JavaScript Origins": http://myplatform.com
- Click on "Create client ID"
- Remember "Client ID" and "Client secret" for configuration below.

![Google Drive API Access](https://raw.github.com/exo-addons/cloud-drive-extension/master/readme-resources/google-drive-access.png)


Configuration
-------------

- Open the configuration file of your Platform server /opt/platform-tomcat/gatein/conf/configuration.properties

Add the two following variables :

    clouddrive.service.host=myplatform.com
    clouddrive.google.client.id=00000000000@developer.gserviceaccount.com
    clouddrive.google.client.secret=XXXXXXX

The clouddrive.google.client.id parameter is the Client ID of the service account (available in your Google console, see previous screenshot).
The clouddrive.google.client.secret parameter is Client Secret of the service account (available in your Google console, see above).


Run Platform
------------

Switch to a folder with your Platform and start it.

    cd /opt/platform-tomcat
    ./start_eXo.sh
    

Use Cloud Drive extension
=========================

In running Platform go to Documents app, open Personal Documents folder root and click "Connect your Google Drive".
Detailed steps described in this post [eXo Add-on in Action: Connecting your Google Drive to eXo Platform](http://blog.exoplatform.com/2013/02/28/exo-add-on-in-action-connecting-your-google-drive-to-exo-platform).














