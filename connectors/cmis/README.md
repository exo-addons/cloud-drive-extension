eXo Cloud Drive CMIS Connector
==============================

CMIS connector for eXo Cloud Drive. This connector can connect any CMIS compliant repository as a cloud drive in eXo documents. 
CMIS support embedded into core Cloud Drive by default.

To connect CMIS repository you need following: 
- an URL of AtomPub binding of your CMIS server
- username and password to authenticate to the server
- if the server has several repositories you'll need to select an one: each repository can be connected as a separate cloud drive.

Important notice: username and password will be sent in plain text, thus enasure you are connecting via secure connection in production. 

Configuration
-------------

CMIS connector doesn't require any configuration for getting started. But you may find useful to predefine list of your CMIS servers for better user experience. When user try connect CMIS repository as a cloud drive, the add-on will show a form where need fill server URL and username with password. If several repositories found on the server, then user will need to choose one of them. Predefined services allow configure list of named URLs in the form available from dropdown menu of _Service URL_ field. 
Name of predefined service, in conjunction with the username and repository name, later will be used for cloud drive folder naming when connecting in eXo. It is form: ${Predefined Name} - ${Repository Name} - ${User Title}. When no predefined service was used (user entered an URL manually), then drive folder name will be: ${Vendor Name} CMIS - ${Repository Name} - ${User Title}. An username will be used as user title. But connectors extended from the CMIS one, can change this behaviour to offer more specific format.

![CMIS login - predefined services](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/cmis/cmis-login-predefined.png)

Predefined services a part of Cloud Drive add-on and they can be configured via connector plugin. This method good on development level, when need add predefined services to the packaged connector (e.g. when extending a connector).
The CMIS connector additionally allows configure predefined AtomPub bindings via settings in eXo properties. Below both ways described with sample configuration.

#### Adding predefined services via connector plugin ####
Create eXo container configuration file and add _CloudDriveService_ component plugin there as shown below. Place host name and port of your CMIS server in an URL, give a name to your predefined connection. 

```xml
  <!-- CMIS connector plugin -->
  <external-component-plugins>
    <target-component>org.exoplatform.clouddrive.CloudDriveService</target-component>
    <component-plugin>
      <name>add.clouddriveprovider</name>
      <set-method>addPlugin</set-method>
      <type>org.exoplatform.clouddrive.cmis.CMISConnector</type>
      <init-params>
        <object-param>
          <name>predefined-services</name>
          <object type="org.exoplatform.clouddrive.CloudDriveConnector$PredefinedServices">
            <field name="services">
              <collection type="java.util.LinkedHashSet">
                <value>
                  <object type="org.exoplatform.clouddrive.cmis.CMISProvider$AtomPub">
                    <field name="name">
                      <string>Product Team</string>
                    </field>
                    <field name="url">
                      <string>http://products.acme.com/_vti_bin/cmis/rest?getRepositories</string>
                    </field>
                  </object>
                </value>
                <value>
                  <object type="org.exoplatform.clouddrive.cmis.CMISProvider$AtomPub">
                    <field name="name">
                      <string>Sales Team</string>
                    </field>
                    <field name="url">
                      <string>http://sales.acme.com/_vti_bin/cmis/rest?getRepositories</string>
                    </field>
                  </object>
                </value>
                <value>
                  <object type="org.exoplatform.clouddrive.cmis.CMISProvider$AtomPub">
                    <field name="name">
                      <string>BCG - US</string>
                    </field>
                    <field name="url">
                      <string>https://circle.bcghq.com/_vti_bin/cmis/rest?getRepositories</string>
                    </field>
                  </object>
                </value>
              </collection>
            </field>
          </object>
        </object-param>
      </init-params>
    </component-plugin>
  </external-component-plugins>
```

Save this file as *configuration.xml* in your eXo Platform configuration directory (known as _exo.conf.dir_), for Tomcat bundle it is by default _gatein/conf/portal/${PORTAL_NAME}/configuration.xml_ (where ${PORTAL_NAME} is a portal container name, *portal* by default). If you already have *configuration.xml* in configuration directory, then rename the file to something else (e.g. cloud-drive-configuration.xml) and import it from your config file.

```xml

<import>file:/${exo.conf.dir}/portla/portal/cloud-drive-configuration.xml</import>

```

#### Adding predefined services in eXo properties ####
The same effect as via connector plugin, but much simpler, possible via setings in eXo properties file. On Platform 4.0 you'll need a new property to existing _configuration.properties_, on Platform 4.1 create (if not already done) your own _exo.properties_ and add described property to it.

```ini
clouddrive.cmis.predefined=Product Team:http://products.acme.com/_vti_bin/cmis/rest?getRepositories\n\
Sales Team:http://sales.acme.com/_vti_bin/cmis/rest?getRepositories\n\
BCG - US:https://circle.bcghq.com/_vti_bin/cmis/rest?getRepositories

```

Predefined service consists of a string started with a name of service and its URL followed after a colon. Several services can be configured: each service should be split from others by a new line character (\n). Service name cannot contain a colon in name (everything between it and \n or end of line will be treated as an URL). eXo properties it is Java Property file. Follow its markup rules when creating settings: escape with '\', split in multiline also by '\'. 

Additionally, via eXo properties, it's possible to avoid using predefined services from connector plugin configuration. Set _override_ flag as below to reset predefined CMIS connector servers. 

```ini
clouddrive.cmis.predefined.override=false
```
Note that settings keys (on the left) are case-sensitive and must be in lower case. 

Development
-----------

This project consist of two modules: services and webapp. 

As described in core Cloud Drive documentation, there is a single entry point to the Cloud Drive API - _CloudDriveService_ component. Use this component to create or access your drives. As CMIS connector doesn't built on OAuth2 authentication flow, it requires additional steps to connect a repository as cloud drive. Below adapted sample from core add-on but with CMIS connector specific. 

Having _CloudDriveService_ components in the hands you can use it to get available providers and proceed with a flow to connect your remote drive:
* obtain instance of cloud provider via _getProvider(String id)_ with required connector id (_cmis_ here).
* authenticate your user _authenticate(CloudProvider cloudProvider, String key)_, this method historically build for OAuth2 flow, it's why for CMIS we have _CodeAuthentication_ component which is a part of CMIS connector and works as a helper for UI (connect form).
* having cloud user instance you can connect remote drive (CMIS repository) to any JCR node (it should be nt:folder). Core add-on doesn't care about what is it a node and where it located. Limitation to Personal Documents placed on WebUI level via component filter _PersonalDocumentsFilter_ for regarding action components in ECMS UI. Choose for a node from your requirements. Use method _createDrive(CloudUser user, Node driveNode)_ to create cloud drive in this node. The add-on will use it as a root of the remote drive and will manage its content respectively. Under creation it assumes initial fetch of all remote files and creation of meta-objects as sub-nodes in the JCR.
* if you need find/test if some node already are connected cloud drive - use _findDrive()_ methods for this purpose.
* how synchronization works: it should be invoked from outside the core via a method on drive instance _CloudDrive.synchronize()_. Cloud Drive integration with ECMS UI does this automatically thanks to Javascript client loaded in ECMS pages as part of the add-on WebUI components managed by set of filters (_CloudDriveFilter_, _CloudFileFilter_, _BelongToCloudDriveFilter_). If you'll open your node in ECMS file explorer they should work for you and you don't need anything to invoke the synchronization. For other pages you'll need use Javascript client to invoke synchronization according your app logic.

Let's get close to CMIS specific, as it doesn't use OAuth2 flow, it's where we need authorization code to connect the user. You properly get the _CodeAuthentication_ component and obtained a code from it. Use this code as described above and obtain _CloudUser_ instance, then create a drive with it and your target JCR node.

```java
// Your JCR node of type nt:folder, it will be a root folder of cloud drive in eXo
Node node = ...;
// get eXo container
ExoContainer myContainer = ExoContainerContext.getCurrentContainer(); 
// obtain authentication code from CMIS authenticator
CodeAuthentication codeAuth = (CodeAuthentication) myContainer.getComponentInstance(CodeAuthentication.class); 
String code = codeAuth.authenticate(codeAuthServiceURL, codeAuthUser, codeAuthPasswordText); 
// use CloudDriveService 
CloudDriveService cloudDrives = (CloudDriveService) myContainer.getComponentInstance(CloudDriveService.class); 
CloudProvider cmisProvider = cloudDrives.getProvider("cmis"); 
CloudUser cmisUser = cloudDrives.authenticate(cmisProvider, code);
// reference your code to target CMIS repository
codeAuth.setCodeContext(code, repository);
CloudDrive cmisRepoDrive = cloudDrives.createDrive(cmisUser, node); 
// you may store cmisRepoDrive for later use, e.g. add listeners, get its files or invoke sync explicitly
```

For information about connectors development refer to [Connector API](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md). 

