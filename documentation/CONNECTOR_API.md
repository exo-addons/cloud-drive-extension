eXo Cloud Drive Connector API
=============================

Cloud Drive add-on supports various cloud providers via extensible Connector API. A connector it is a complex of Java interfaces implemented by the connector code and client scripts, pages, styles, other files connected together by conventions. Connector API allows develop connectors to new cloud providers withour changing the core code.
Cloud Drive add-on it is a [portal extension](http://docs.exoplatform.com/PLF40/PLFDevGuide.eXoPlatformExtensions.html) to Platform. Each _connector it is also a portal extension_ that depends on the core add-on extension.

For existing embedded connectors check [connectors page](https://raw.github.com/exo-addons/cloud-drive-extension/master/connectors/README.md).
Below it is described how to create a new connector using Connector API.

Getting started
===============

Depending on needs and features a cloud provider exposes, the Cloud Drive connector can provide a basic API implementation (Java API) or also contain client UI parts such as new forms, templates, scripts or styles. Connector also may provide internationalization resources.

Connector it is a portal extension that depends on Cloud Drive extension. As any other [Platform extension](http://docs.exoplatform.com/PLF40/PLFDevGuide.eXoPlatformExtensions.html), each connector consists of services JAR and web application WAR. Services JAR contains Java API implemenation and related resources. Web app WAR contains the connector configuration and, optionally, styles for supported file types, JCR namespaces and nodetypes, required UI pages, templates and scripts. 

Cloud Drive integrated with eXo Documents (eXo ECMS project) to make possible most of operations on remote cloud files as on local documents. Cloud Drive itself introduces an abstraction to represent documents from different souces in cloud in unificated form inside eXo. Thus files from Google Drive, Box.com, or any other connector are accessible through the single API which includes server-side Java API, RESTful services and client-side styles and Javascript. All this has a name - _Connector API_. When creating a new connector need implement this API following the conventions and then deploy the connector to the Platform. 

To bootstrat the development there is a [_template_ connector](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors/template): it is an empty connector with structure that already follow the conventions and has API stubs to implement for actual cloud provider.

Architecture
============

Being a portal extension, the Cloud Drive connector it is a plugin of the add-on's core component `CloudDriveService`. Plugin should be configured in the connector extension and implement `CloudDriveConnector` interface. It is a programmatical entry point for each connector. Static resources and configurations will be loaded by portal extension mechanism, the single thing that required to make them accessible it's follow the conventions of the portal and this Connector API. To make the plugin to work need implement major parts of the Java API and provide required configuration.

Below a diagram of Cloud Drive architecture. Connector API marked as red (major parts) and blue (optional parts). Major parts should be implemented by any connector. Optional, at the other hand, may be implemented for additional features like automatic synchronization, UI or other client customization.

![Cloud Drive architecture and Connector API parts](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/connectors-api/architecture-connector.png) 

Cloud Drive API works as a layer between storage in JCR and cloud drive with files in cloud. Cloud drive, when connected in eXo Documents, represents a folder, `nt:folder` with `ecd:cloudDrive` mixin, in user's personal documents folder. All files in the drive folder are `nt:file` with `ecd:cloudFile` mixin. ECMS services see cloud drive and its files as normal local documents and thanks to UI integration these files can be open embedded in Platform page or in a new page on cloud site. Add-on also offers support of many ECMS features. 

Conventions
-----------

Naming plays important role in Cloud Drive organization. Below a set of conventions required to follow when implementing a new connector.

**Provider ID**

Connectors are pluggable and identified by provider ID. This ID used in file, module, URLs, namespace names. Thus consider provider ID with following restrictions:
* ANSI characters without spaces, dashes, underscore and punctuation - it should be a single word (e.g. gdrive, box, dropbox, onedrive),
* lowercase - don't use character in upper case.

**Artefact names**

Cloud Drive connector consists of services JAR and web app WAR arteracts. There is not strict requirement for JCR file name, but WAR's `display-name` should follow this convention: 
* service JAR name: exo-clouddrive-PROVIDER\_ID-services-VERSION.jar,
* web app WAR name: cloud-drive-PROVIDER\_ID.war 
* WAR's `display-name`: cloud-drive-PROVIDER\_ID 

**JCR namespace**

If connector requires write specific data (metadata) to JCR storage, it is recommended to use dedicated namespace for nodes and properties. For instance, Box stream history stored in `box:streamHistory`, it uses "box" JCR namespace that well isolate "streamHistory" name from other properties that can exist on the file. This JCR namespace uses a provider ID chosen for the connector.

**Java class names**

It's recommended to name connector classes using provider ID. This way configuration will be human-readable and simpler to maintain.

**Javascript module name**

When need provide additional logic for your connector in client browser, it's possible to define an [AMD](http://en.wikipedia.org/wiki/Asynchronous_module_definition) [module](http://docs.exoplatform.com/PLF40/sect-Reference_Guide-Javascript_Development-JavaScript_In_GateIn-GMD_Declaring_Module.html) in the Platform, it will be loaded by Cloud Drive add-on when your connector will initialize its provider in the browser. Javascript module name should have a name in form of `cloudDrive.PROVIDER_ID`, e.g. "cloudDrive.box" for Box. Cloud Drive client automaticaly loads connector module if it is defined in the extension, no special action required.

**CSS style file**

To provide branded styles for your connector and its files you may need load a CSS to the browser when connected drive is open in Documents app. Cloud Drive client automaticaly loads connector styles from the web app of the extension by its name, no special action required. As mentioned above web app name should be `cloud-drive-PROVIDER_ID`. Then CSS file will be loaded by path: "/cloud-drive-PROVIDER_ID/skin/cloud-drive.css".

**CSS class names**

eXo ECMS automatically generate CSS class names from [Action UI](http://docs.exoplatform.com/PLF40/PLFRefGuide.PLFDevelopment.Extensions.UIExtensions.UIExtensionComponents.html) components. 
Additionaly Cloud Drive adds following classes:
* for "Connect Cloud Documents" dialog, icons for connectors: `uiIconEcmsConnectDialog-PROVIDER_ID`,
* for icons of connected drives in Icon, List and Admin views: `uiIcon64x64CloudDrive-PROVIDER_ID`, `uiIcon16x16CloudDrive-PROVIDER_ID` and `uiIcon24x24CloudDrive-PROVIDER_ID`, 
* for "Open Cloud File" action: `uiIcon16x16CloudFile-PROVIDER_ID`. 
Provide this class in connector CSS file. 

**File CSS icons**

Cloud files may have provider specific mimetypes. Cloud Drive uses file types as in cloud and eXo ECMS will generate file CSS classes based on those mimetypes. For instance, Google Drive Spreadsheet type `application/vnd.google-apps.spreadsheet` will have generated classes for views in ECMS (Admin/List view, tree icons and Icon view): `uiIcon24x24applicationvndgoogle-appsspreadsheet`, `uiIcon16x16applicationvndgoogle-appsspreadsheet` and `uiIcon64x64applicationvndgoogle-appsspreadsheet`. 
Connector can provides CSS classes for supported mimetypes to override default ECMS file icons.

**ECMS menu actions**

eXo ECMS uses menu action names and respectively related internationalization resources based on action class name. For example: action class `ConnectBoxActionComponent`, configured as UI extension with name `ConnectBox`, has action name in resource "ConnectBox" and name for administration "connectBox". When action name based on provider ID it simplifies configuration and resources organization.

_WARNING_

For backward compatibiltiy conventions have an exception in UI for the first connector - Google Drive: instead of provider ID it uses "GoogleDrive" name in action name and related internationalization resources.

**Web-services**

When creating a web-service dedicated to a connector (if required), use an endpoint with its provider ID: `/clouddrive/drive/PROVIDER_ID`. It is optional recommendation as this path may be used in future for connectors/features discovery.

Connector API
-------------

Connector API consists of following parts:
* Java API (major part)
* RESTful web services (optional)
* UI extensions for dedicated menu etc. (optional)
* CSS styles (optional) 
* Javascript API (optional)

Each connector should be packaged as a portal extension with its configuration in it.

Connector extension
-------------------

Cloud Drive connector should be a portal extension that simply can be installed and uninstalled. The core add-on already care about ECMS menu actions cleanup when Cloud Drive uninstalled. But if a new connector will add extra UI components what can be referenced by the Platform on startup or in runtime, the connector should care about a clenup of such components on uninstallation (this could be done on a server stop).

All required configurations and resources should be packaged in the connector extension files. Connector dependencies should be outside the WAR file and deployed directly to _libraries_ folder of the Platform server (this already will be done by installation via eXo Add-ons Manager or extension installer script).

Cloud Drive connector consists of following arteracts: 
* service JAR (exo-clouddrive-PROVIDER\_ID-services-VERSION.jar) with the extension configuration, Java API components implementation and related resources (internationalization bundles etc.)
* web app WAR (cloud-drive-PROVIDER\_ID.war) with connector components configuration and required web resources.

Configuration
-------------

Connector extension should depend on Cloud Drive extension in its `PortalContainerConfig` settings. Connector's services JAR needs following configuration in `conf/configuration.xml` file, using `PortalContainerDefinitionChange$AddDependenciesAfter` type, to add itself as dependency to the Cloud Drive (replace PROVIDER_ID with your value):

```xml
<configuration xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.exoplaform.org/xml/ns/kernel_1_0.xsd http://www.exoplaform.org/xml/ns/kernel_1_0.xsd"
  xmlns="http://www.exoplaform.org/xml/ns/kernel_1_0.xsd">

  <!-- Portal extension configuration for YOUR CONNECTOR EXTENSION -->
  <external-component-plugins>
    <target-component>org.exoplatform.container.definition.PortalContainerConfig</target-component>
    <component-plugin>
      <name>Change PortalContainer Definitions</name>
      <set-method>registerChangePlugin</set-method>
      <type>org.exoplatform.container.definition.PortalContainerDefinitionChangePlugin</type>
      <priority>1500</priority>
      <init-params>
        <value-param>
          <name>apply.default</name>
          <value>true</value>
        </value-param>
        <object-param>
          <name>change</name>
          <object type="org.exoplatform.container.definition.PortalContainerDefinitionChange$AddDependenciesAfter">
            <field name="dependencies">
              <collection type="java.util.ArrayList">
                <value>
                  <string>cloud-drive-PROVIDER_ID</string>
                </value>
              </collection>
            </field>
            <field name="target">
              <string>cloud-drive</string>
            </field>
          </object>
        </object-param>
      </init-params>
    </component-plugin>
  </external-component-plugins>
</configuration>
```

Connector's WAR configuration contains:
* web app descriptor (web.xml) with proper `display-name` (as WAR name) and other [portal settings](http://docs.exoplatform.com/PLF40/PLFDevGuide.eXoPlatformExtensions.CreatingExtensionProject.html) (follow the template extension),
* components configuration in `WEB-INF/conf/configuration.xml`, what includes connector as plugin of `CloudDriveService` and optionally JCR namespaces and nodetypes, UI extensions, resource bundles etc.

If the configuration requires in-place values (such as host name, authentication keys etc), these values can be [variablized](http://docs.exoplatform.com/PLF40/Kernel.ContainerConfiguration.VariableSyntaxes.html) in XML configuration of the connector and then provided in _configuration.properties_ or/and via JVM parameters of the Platform server.

Example of Google Drive connector plugin, major provider _name_ and _ID there with OAuth2 credentials and host paramaters variablized there:

```xml
  <!-- Google Drive connector plugin -->
  <external-component-plugins>
    <target-component>org.exoplatform.clouddrive.CloudDriveService</target-component>
    <component-plugin>
      <name>add.clouddriveprovider</name>
      <set-method>addPlugin</set-method>
      <type>org.exoplatform.clouddrive.gdrive.GoogleDriveConnector</type>
      <init-params>
        <properties-param>
          <name>drive-configuration</name>
          <property name="provider-id" value="gdrive" />
          <property name="provider-name" value="Google Drive" />
          <property name="provider-client-id" value="${clouddrive.google.client.id}" />
          <property name="provider-client-secret" value="${clouddrive.google.client.secret}" />
          <property name="connector-host" value="${clouddrive.service.host}" />
          <property name="connector-schema" value="${clouddrive.service.schema:http}" />
        </properties-param>
      </init-params>
    </component-plugin>
  </external-component-plugins>
```

And _configuration.properties_ with these variables:
```
clouddrive.service.schema=https
clouddrive.service.host=mysecureplatform.com
clouddrive.google.client.id=XXXXXX.apps.googleusercontent.com
clouddrive.google.client.secret=ZZZZZZ
```

Java API
========

Java API is mandatory part of any connector. By implementing Java interfaces and extending basic abstract classes your create a new connector and plug it to the Cloud Drive model. 

All local changes to the cloud drive gathered via JCR observation and then these changes applied to remote drive in cloud. Changes from cloud gathered via synchronization invoked by a client code when an user is working in a drive. All this is a part of core add-on. If need listen for changes in cloud drive, it's possible to register `CloudDriveListener` on `CloudDrive` instance. Connector or any other service can get an info about drive events (synchronization, removal, errors).

Another important thing about Cloud Drive - all its _operations are asynchronous_. When connect or synchronize method returns it return a `Command` instance which let monitor the progress, await it and get affected files. Each connector will need to provide command instances for connection and synchronization of a drive.

Authorization
-------------

Cloud Drive relies on OAuth2 for authorization to cloud services. Support of OAuth2 flow added in `CloudDrive` methods and there is a token store abstraction `UserToken` to support access and refresh tokens maintenance. Extend this token abstract class internally in the connector when calling client library and use `UserTokenRefreshListener` to be informed about token changes to store them in JCR (e.g. in a drive folder) or other stores.

Cloud Drive Service
-------------------

Component `CloudDriveService` available in eXo container and it provides top-level methods to connect and get an info about cloud drives. This components also used to register new connectors as its plugins. 

Cloud Connector
---------------

Cloud Drive connector should extend `CloudDriveConnector` class and implement abstract methods:
* `authenticate(String)` - authenticate an user by an access code from its cloud provider (OAuth2 usecase). As result an instance of `CloudUser` will be returned,
* `createProvider()` - create `CloudProvider` instance, this methid used internally by the connector constructor,
* `createDrive(CloudUser user, Node driveNode)` - create `CloudDrive` instance for a given user, this instance will be connected to local storage under given drive node`,
* `loadDrive(Node driveNode)` - load `CloudDrive` from local storage in existing node, this method used on a server start to load existing drives.

Connector also may extend existing methods if required. Connector also can work as a builder for internal API object when it requires an access to configuration paramaters.

Cloud Provider
--------------

A `CloudProvider` class describes major parameters: provider name and ID. It is a configuration holder in runtime. Each connector may provide specific properties for its cloud service only. Provider also acts as an authentication parameters builder (auth URL, SSO, etc.) and define behaviour on cloud errors (retry or not on an error). 

Cloud User
----------

Cloud user described by `CloudUser` abstract class. Commons such as name, user ID, email and cloud provider it is a basics required by Cloud Drive. Cloud user instance also may work as a holder of internal API created by `CloudDriveConnector` as a facade on a libray to access cloud services. As cloud user available from `CloudDrive`, it's possible to obtain the internal API instance from a method by casting to exact class without making an access to API public. See `GoogleUser` for example of such implementation.

Cloud Drive
-----------

It is a main class in infrastructure of locally connected drive. `CloudDrive` abstraction predefines a contract of _local cloud drive_ - an entity that describe drive as a local folder with files and operations on it, this class doesn't focus on a local storage type. And storage centric abstract class `JCRLocalCloudDrive` implements all common operations of a drive - this class should be extended by a connector implementation.

Asynchronous commands support already implemented in `JCRLocalCloudDrive` as inner classes `ConnectCommand` and `SyncCommand`. Extend these protected classes in your local drive implementation with the cloud logic. And return their instances respectively in methods `getConnectCommand()` and `getSyncCommand()`. Command will be invoked in a thread pool by the local drive.

Another internal part to implement in a connector - `CloudFileAPI` interface. It is a set of methods required for drive synchronization between local nodes and cloud side. Basics already implemented in inner class `AbstractFileAPI`, but connector needs to provide exact calls to a cloud API it represents. For some providers it's possible that several operations lead to a single method, but for others it's different calls. File API respect this fact by offering different methods for files and folders, but final implementation may realy on single logic internally. Implement `createFileAPI()` method to return actual API instance.

There is optional support of drive state in method `getState()` - a specific object that describe the drive's current state including changes notification and other internal mechanisms what may be required in others apps and clients. If connector doesn't support states it should return `null`. Returned object should support serialization to JSON in RESTful service to be successfully returned to a client. This means its class should be statically available and acts as a POJO (with getters).

Actual cloud drive implementation also should provide `getUser()` method to return `CloudUser` instance of the drive. 

Cloud drive provides methods to refresh and update OAuth2 tokens to access cloud drive services:
* `refreshAccess()` will be called on each drive synchronization invokation
* `updateAccess(CloudUser newUser)` when refresh token outdated and drive initiated autentication window for an user and he confirmed the access, this method should update internal tokens from a new user object. User object may holds access tokens in internal API object as described above in Cloud User section,
* cloud drive also may implement `UserTokenRefreshListener` to be informed when tokens updated by the cloud library and store them in local store (JCR).

Connected drive initialization may be customized by overidding `initDrive()` method, but don't forget to call super's method first. This method useful to store additional, connector specific data in a drive node.

To sync changes made locally and sync them to a cloud side in proper order, the cloud drive maintains an abstraction - changes ID. It is _long_ value and it is used internally. As such abstraction already exists in many cloud providers (Google Drive has 'largest change id', Box has 'stream position') - implementation of it should be done in an actual connector code. Methods `readChangeId()` and `saveChangeId(Long id)` are for reading and saving the identifier.

Beside persistent metadata of a drive there is a need of dynamically constructed links. Such links can, but not must, be generated in runtime to support such specific features as single-sign-on, cross-origin resource sharing, enterprise domain etc. This decision is up to an actual connector implementation via drive methods:
* Previw link for use in embedded frame in Platform Documents app: `previewLink(String link)`. By default this method returns the same as given preview link. Actual connector implementation may override its logic.
* Editing link to let edit a file in embedded frame in Platform Documents: `editLink(String link)`. By default this method returns the same as given file link (`CloudFile.getLink()`). Actual connector implementation may override its logic. Implementation should return `null` if editing not supported. 
If editing link availabel then the file page in Documents will have a switch between _View_ and _Edit_ modes, otherwise a file will be shown in iframe with its preview `CloudFile.getPreviewLink()` or, if not found, the API link `CloudFile.getLink()`.

Cloud File
----------

Connector API describes cloud file in a POJO interface `CloudFile`. It is an abstraction on file entities existing in different cloud APIes required for Cloud Drive functions. Among others there are main parameters of a file: ID, title/name, link, dates of creation and modification, author and last editor, file type and marker of a folder. File also may offer preview, editing and thumbnail links if its provider supports these features. There is also a flag, `isSyncing()`, letting to know if a file is currently syncing (e.g. uploading to a cloud).

Cloud Drive core implements cloud file in `JCRLocalCloudFile` class. It is an implementation based on JCR backend of a cloud drive and it additionally introduces a method `getNode()` to access a file node. Its instances will be returned when getting a file from `CloudDrive` for example.
Interface `CloudFile` doesn't need to be implemented by a connector if there is not such requirement. But if such need happens, take in account that extension of the existing `JCRLocalCloudFile` may not work with eXo WS REST engine. 
For use in web-services layer the core already implemented `CloudFile` in `LinkedCloudFile`, to describe symlinks to files, and in `AcceptedCloudFile` for locally crated but not yet uploaded to cloud files.

Web services
============

Cloud Drive offers following kinds of RESTful web-services:
* `/clouddrive/connect` - a service to connect a new drive and control its connection progress (used by Javascript API for Documents menu)
* `/clouddrive/drive` - a service to get an info about a cloud drive and its files (used by Javascript API in Documents views)
* `/clouddrive/features` - a service providing discovery of Cloud Drive features (see more in Features API).

A connector may need, but not required, to provide additional services used by its UI or other clients or apps. It is recommended to keep all related service endpoints with a path `/clouddrive/drive/PROVIDER_ID` (e.g. /clouddrive/drive/cmis). This path may be used in future for connectors/features discovery.

Template project contains `SampleService` class which implements an abstract reading of file comments by a client script (`readComments()` in `cloudDrive-PROVIDER_ID.js` script).

UI extensions
=============

Cloud Drive provides integration with Documents app via [UI extension](http://docs.exoplatform.com/PLF40/PLFRefGuide.PLFDevelopment.Extensions.UIExtensions.html) mechanism of eXo Platform.

Cloud Drive has two kinds of menu actions: a common dialog where user can chose which provider to connect and menu actions dedicated to some connector. The last, dedicated actions, is optional and it's up to a connector to provider them or not. Indeed it's simply to provide such support as need only extend a Java class `BaseConnectActionComponent` and use provider ID in it: in internal method and for the class name. 

eXo ECMS uses menu action names and respectively related internationalization resources based on action class name. An example: for action class `ConnectBoxActionComponent`, configured as UI extension with name `ConnectBox`, the action name in resource is "ConnectBox" and name for Content administration action is "connectBox".

Cloud Drive UI uses `CloudDriveContext` helper to initialize its UI components and Cloud Drive Javascript API. This helper adds Javascript code to initialize the client and setups available providers on the Documents app pages (via RequireJS support, see also below). When adding a new UI component or action extend `BaseConnectActionComponent` and implement `getProviderId()` method, then this component will initialize context transparently. If not possible to extend, invoke `CloudDriveContext.init(portalRequestContext, workspace, nodePath)` on render phase (like in `renderEventURL()` method) of your component. If given workspace and node path belongs to some cloud drive, then the context will be initialized as described above.

Javascript API
==============

Cloud Drive comes with Javascript client module for integration in eXo Platform web applications. Client provides support of drive connection and synchronization in user interface. It also customizes Documents client app to render cloud drive properly: drive and files styling, context and action bar menus, embedded file preview and editing. 

Cloud Drive client loads its Javascript using [RequireJS](requirejs.org/docs/api.html) framework integrated in [eXo Platform](http://docs.exoplatform.com/PLF40/sect-Reference_Guide-Javascript_Development-JavaScript_In_GateIn-GMD_Declaring_Module.html). The client it is an [AMD module](http://en.wikipedia.org/wiki/Asynchronous_module_definition) with name `cloudDrive`, it will be loaded when a connector will initialize its provider on the Documents app page (via UI extension context). The module itself will load requires resources for drive providers (CSS, Javascript module etc.). 

Javascript API pluggable and specific logic can be provided by a connector to invoke synchronization on drive state change. When client module initialized it starts automatic synchrinization and check if a connector module exists for a drive provider. Connector module name should have a name in form of `cloudDrive.PROVIDER_ID` to be loaded by the client automaticaly. If module loaded successfully, then this module can provide asynchronous invokation on the drive state change.

Connector module, as any other scripts, can use `cloudDrive` as AMD dependency. Most of Cloud Drive methods return [jQuery Promise](http://api.jquery.com/deferred.promise/) object which can be used for callbacks registration for connect or synchronization operations. 

There are also `cloudDriveUtils` module which offers cookies management, CSS loading and unified logger. Refer to source code of modules for more information.

Drive state monitoring
----------------------

It's possible to manage how the Cloud Drive client will invoke drive update (synchronization and related opeations). When cloud drive folder is open by an user, the client runs automatic synchronization of the drive. By default client will run periodic synchronization starting from syncing each 20 seconds for 30 minutes period (after 10 minututes it will sync every 40 seconds). It is an independent and working but not efficient way. If cloud service can provide an event based way of getting a drive state, it's better to use it.

If connector provides a Javascript module and Cloud Drive client can load it, it will check if returned module has a method `onChange()` and if it does, then object returned by this method will be used as an initiator for drive synchronization. If method doesn't exists then default behaviour will be used. 

Cloud Drive client expects that connector's `onChange()` method returns [jQuery Promise](http://api.jquery.com/deferred.promise/) and then it uses the promise to initiate synchronization process. When the promise will be resolved by the connector (its `.resolve()` invoked), it will mean the drive has new remote changes and client will start synchronization and then refresh the UI. If promise rejected (`.reject(error)` invoked) then automatic synchronization will be canceled and a new synchronization will be attempted when an user perform an operation on the Documents page (navigate to other file or folder, use Cloud Drive menu). Thanks to use of jQuery Promise drive state synchronization runs asynchronously, only when an actual change will happen in the drive.

Deploy to eXo Platform
======================

To be successfully deployed to eXo Platform, Cloud Drive connector should be packaged as a [portal extension](http://docs.exoplatform.com/PLF40/PLFDevGuide.eXoPlatformExtensions.html), a zip archive containing following files:
* service JAR name: exo-clouddrive-PROVIDER\_ID-services-VERSION.jar,
* web app WAR name: cloud-drive-PROVIDER\_ID.war 
Where PROVIDER\_ID is for actual provider ID and VERSION for the connector version.

Use eXo Platform [Add-ons Manager](https://github.com/exoplatform/addons-manager) or older Extensions manager script to install a connector. Changes of _configuration.properties_ should be performed manually by a person who administrate the server.

A [_template_ connector](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors/template) project already contains Maven modules with Cloud Drive dependencies and packaging. Copy template sub-folder to your new location and replace `PROVIDER_ID`, `PROVIDER_NAME` and `ConnectTemplate` with actual values in source files and configuration, rename respectively class names and variables: e.g. `cmis`, `CMIS` and `ConnectCMIS`. Fill the sources with a logic to work with your connector cloud services. Then build the connector and use its packaging artifact as a connector extension.








