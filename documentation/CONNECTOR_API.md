eXo Cloud Drive Connector API
=============================

Cloud Drive add-on supports various cloud providers via extensible Connector API. A connector it is a set of Java interfaces implemented by the connector code and client scripts, pages, styles, other files connected together by conventions. Connector API allows develop connectors to new cloud providers without changing the core code.
Cloud Drive add-on it is a [portal extension](http://docs.exoplatform.com/PLF40/PLFDevGuide.eXoPlatformExtensions.html) to Platform. Each _connector it is also a portal extension_ that depends on the core add-on extension.

For existing embedded connectors check [connectors page](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors).
Below it is described how to create a new connector using Connector API.

Getting started
===============

Depending on needs and features a cloud provider exposes, the Cloud Drive connector can provide a basic API implementation (Java API) or also contain client UI parts such as new forms, templates, scripts or styles. Connector also may provide internationalization resources.

Connector it is a portal extension that depends on Cloud Drive extension. As any other [Platform extension](http://docs.exoplatform.com/PLF40/PLFDevGuide.eXoPlatformExtensions.html), each connector consists of _services JAR_ and _web application WAR_. Services JAR contains Java API implemenation and related resources. Web app WAR contains the connector configuration and, optionally, styles for supported file types, JCR namespaces and nodetypes, required UI pages, templates and scripts. 

Cloud Drive integrated with eXo Documents (eXo ECMS project) to make possible most of operations on remote cloud files as on local documents. Cloud Drive itself introduces an abstraction to represent documents from different souces in cloud in unificated form inside eXo. Thus files from Google Drive, Box.com, or any other connector are accessible through the single API which includes server-side Java API, RESTful services and client-side styles and Javascript. All this has a name - _Connector API_. When creating a new connector need implement this API following the conventions and then deploy the connector to the Platform. 

To bootstrat the development there is a [_template_ connector](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors/template): it is an empty connector with structure that already follow the conventions and has API stubs to implement for actual cloud provider.

Architecture
============

Being a portal extension, the Cloud Drive connector it is a plugin of the add-on's core component `CloudDriveService`. Plugin should be configured in the connector extension and implement `CloudDriveConnector` interface. It is a programmatic entry point for each connector. Static resources and configurations will be loaded by portal extension mechanism, the single thing that required to make them accessible it's follow the conventions of the portal and this Connector API. To make the plugin to work need implement major parts of the Java API and provide required configuration.

Below a diagram of Cloud Drive architecture. Connector API marked as red (major parts) and blue (optional parts). Major parts should be implemented by any connector. Optional, at the other hand, may be implemented for additional features like automatic synchronization, UI or other client customization.

![Cloud Drive architecture and Connector API parts](https://raw.github.com/exo-addons/cloud-drive-extension/master/documentation/connectors-api/architecture-connector.png) 

Cloud Drive API works as a layer between storage in JCR and cloud drive with files in cloud. Cloud drive, when connected in eXo Documents, presents a new folder in user's personal documents, its type `nt:folder` with `ecd:cloudDrive` mixin. All files in the drive folder are `nt:file` with `ecd:cloudFile` mixin. ECMS services see cloud drive and its files as normal local documents and thanks to UI integration these files can be open embedded in Platform page or in a new page on cloud site. Add-on also offers support of many ECMS features. 

Conventions
-----------

Naming plays important role in Cloud Drive organization. Below a set of conventions required to follow when implementing a new connector.

**Provider ID**

Connectors are pluggable and identified by provider ID. This ID used in file, module, URLs, namespace names. Thus consider provider ID with following restrictions:
* ANSI characters without spaces, dashes, underscore and punctuation - it should be a single word (e.g. gdrive, box, dropbox, onedrive),
* lowercase - don't use character in upper case.

**Artifact names**

Cloud Drive connector consists of services JAR and web app WAR arteracts. There is not strict requirement for JCR file name, but WAR's `display-name` should follow this convention: 
* services JAR name: exo-clouddrive-PROVIDER\_ID-services-VERSION.jar,
* web app WAR name: cloud-drive-PROVIDER\_ID.war 
* WAR's `display-name`: cloud-drive-PROVIDER\_ID 

**JCR namespace**

If connector requires write specific data (metadata) to JCR storage, it is recommended to use dedicated namespace for nodes and properties. For instance, Box stream history stored in `box:streamHistory`, it uses "box" JCR namespace that well isolate "streamHistory" name from other properties that can exist on the file. This JCR namespace uses a provider ID chosen for the connector.

**Java class names**

It's recommended to name connector classes using provider ID. This way configuration will be human-readable and simpler to maintain.

**Javascript module name**

When need to provide an additional logic for your connector in client browser, it's possible to define an [AMD](http://en.wikipedia.org/wiki/Asynchronous_module_definition) [module](http://docs.exoplatform.com/PLF40/sect-Reference_Guide-Javascript_Development-JavaScript_In_GateIn-GMD_Declaring_Module.html) in the Platform, it will be loaded by Cloud Drive add-on when your connector will initialize its provider in the browser. Javascript module name should have a name in form of `cloudDrive.PROVIDER_ID`, e.g. "cloudDrive.box" for Box. Cloud Drive client automaticaly loads connector module if it is defined in the extension, no special action required.

**CSS style file**

To provide branded styles for your connector and its files you may need load a CSS to the browser when connected drive is open in Documents app. Cloud Drive client automaticaly loads connector styles from the web app of the extension by its name, no special action required. As mentioned above web app name should be `cloud-drive-PROVIDER_ID`. Then CSS file will be loaded by path: "/cloud-drive-PROVIDER_ID/skin/clouddrive.css".

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

This conventions have an exception for backward compatibiltiy for the first connector - Google Drive: instead of provider ID it uses "GoogleDrive" name in UI action name and related internationalization resources.

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

Cloud Drive connector should be a portal extension that simply can be installed and uninstalled. The core add-on already care about ECMS menu actions cleanup when Cloud Drive uninstalled. But if a new connector will add extra UI components what can be referenced by the Platform on startup or in runtime, the connector should care about a cleanup of such components on uninstallation (this could be done on a server stop).

All required configurations and resources should be packaged in the connector extension files. Connector dependencies should be outside the WAR file and deployed directly to _libraries_ folder of the Platform server (this already will be done by installation via eXo Add-ons Manager or extension installer script).

Cloud Drive connector consists of following artiracts: 
* services JAR (exo-clouddrive-PROVIDER\_ID-services-VERSION.jar) with the extension configuration, Java API components implementation and related resources (internationalization bundles etc.)
* web app WAR (cloud-drive-PROVIDER\_ID.war) with connector components configuration and required web resources.

Configuration
-------------

Connector extension should depend on Cloud Drive extension in its `PortalContainerConfig` settings. The services JAR needs following configuration in `conf/configuration.xml` file: use `PortalContainerDefinitionChange$AddDependenciesAfter` type to add itself as a dependency to the Cloud Drive (replace PROVIDER_ID with your value), priority should be higher of 1000:

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

The web app WAR configuration contains:
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

Another important thing about Cloud Drive - all its _operations are asynchronous_. When connect or synchronize method returns it return a `Command` instance which allows to monitor the progress, await it and finally retrieve affected files. Each connector must provide command instances for connection and synchronization of a drive.

Authorization
-------------

Cloud Drive relies on OAuth2 for authorization to cloud services. Support of OAuth2 flow added in `CloudDrive` methods and there is a token store abstraction `UserToken` to support access and refresh tokens maintenance. Extend this abstract class internally in the connector when calling client library and use `UserTokenRefreshListener` to be informed about token changes to store them in JCR (e.g. in a drive folder) or other stores.

Cloud Drive Service
-------------------

Component `CloudDriveService` available in eXo container and it provides top-level methods to connect and get an info about cloud drives. This component also used to register new connectors (as plugins) via configuration (on the start). 

Cloud Connector
---------------

Cloud Drive connector should extend `CloudDriveConnector` class and implement abstract methods:
* `authenticate(String)` - authenticate an user by an access code from its cloud provider (OAuth2 usecase). As result an instance of `CloudUser` will be returned,
* `createProvider()` - create `CloudProvider` instance, this methid used internally by the connector constructor,
* `createDrive(CloudUser user, Node driveNode)` - create `CloudDrive` instance for a given user, this instance will be connected to local storage under given drive node,
* `loadDrive(Node driveNode)` - load `CloudDrive` from local storage in existing node, this method used on a server start to load existing drives.

Connector also may extend existing methods if required. Connector also can work as a builder for internal API object when it requires an access to configuration paramaters. Check in the `TemplateConnector` for a sample.

Cloud Provider
--------------

A `CloudProvider` class describes major parameters: provider name and ID. It is a configuration holder in runtime. Each connector may provide specific properties for its cloud service only. Provider also acts as an authentication parameters builder (auth URL, SSO, etc.) and define behaviour on cloud errors (retry or not on an error). 

Cloud User
----------

Cloud user described by `CloudUser` abstract class. Commons such as name, user ID, email and cloud provider it is a basics required by Cloud Drive. Cloud user instance also may work as a holder of internal API created by `CloudDriveConnector` as a facade on a libray to access cloud services. As cloud user available from `CloudDrive`, it's possible to obtain the internal API instance from its methods by casting to exact class without making an access to API public. See `TemplateUser` or `GoogleUser` for an example of such implementation.

Cloud Drive
-----------

It is a main class in infrastructure of locally connected drive. `CloudDrive` abstraction predefines a contract of _local cloud drive_ - an entity that describe drive as a local folder with files and operations on it, this class doesn't focus on a local storage type. And storage centric `JCRLocalCloudDrive` abstract class, implements all common operations of a drive - this class should be extended by a connector implementation.

Asynchronous commands support already implemented in `JCRLocalCloudDrive` as inner classes `ConnectCommand` and `SyncCommand`. Extend these protected classes in your local drive implementation with the cloud logic. And return their instances respectively in methods `getConnectCommand()` and `getSyncCommand()`. Command will be invoked in a thread pool by the local drive.

Another internal part to implement in a connector - `CloudFileAPI` interface. It is a set of methods required for drive synchronization between local nodes and cloud side. Basics already implemented in the inner class `AbstractFileAPI`, but connector needs to provide exact calls to a cloud API it represents. For some providers it's possible that several operations lead to a single method, but for others it's different calls. File API respect this fact by offering different methods for files and folders, but final implementation may realy on single logic internally. Implement `JCRLocalCloudDrive.createFileAPI()` method to return actual API instance.

There is optional support of drive state via method `getState()` - a specific object that describe the drive's current state including changes notification and other internal mechanisms that may be required in others apps and clients. If connector doesn't support states it should return `null`. Returned object should support serialization to JSON in RESTful service to be successfully returned to a client. This means its class should be statically available and act as a POJO (with getters). There are not requirements to a state object type, thus any method can exist on it - it is useful to expose specific data to a client side.

Actual cloud drive implementation also should provide `getUser()` method to return `CloudUser` instance of the drive. 

Cloud drive provides methods to refresh and update OAuth2 tokens for accessing cloud drive services:
* `refreshAccess()` will be called on each drive synchronization invokation
* `updateAccess(CloudUser newUser)` when refresh token outdated and drive initiated autentication window for an user and the user confirmed the access, this method should update internal tokens from a new user object. User object may holds access tokens in internal API object as described above in Cloud User section,
* cloud drive also may implement `UserTokenRefreshListener` to be informed when tokens updated by the cloud library and store them in local store (JCR).

Connected drive initialization may be customized by overidding `initDrive()` method, but don't forget to call super's method first. This method useful to store additional, connector specific, data in a drive node.

To push changes made locally to a cloud side in proper order, the cloud drive maintains an abstraction - changes ID. It is _long_ value used internally. As such abstraction already exists in many cloud providers (Google Drive has 'largest change id', Box has 'stream position') - implementation of it should be done in an actual connector code. Methods `readChangeId()` and `saveChangeId(Long id)` are for reading and saving the identifier during the work.

Beside persistent metadata of a drive, there is a need of dynamically constructed links. Such links can, but not must, be generated in runtime to support such specific features as single-sign-on, cross-origin resource sharing, enterprise domain etc. This decision is up to an actual connector implementation via drive methods:
* Preview link for use in embedded frame in Platform Documents app: `previewLink(String link)`. By default this method returns the same as given preview link. Actual connector implementation may override this logic.
* Editing link to let edit a file in embedded frame in Platform Documents: `editLink(String link)`. By default this method returns the same as given file link (`CloudFile.getLink()`). Actual connector implementation may override this logic. Implementation should return `null` if editing not supported. 
If editing link availabel then the file page in Documents will have a switch between _View_ and _Edit_ modes, otherwise a file will be shown in iframe with its preview `CloudFile.getPreviewLink()` or, if not found, the API link `CloudFile.getLink()`.

Cloud File
----------

Connector API describes cloud file in a POJO interface `CloudFile`. It is an abstraction on file entities existing in different cloud APIes required for Cloud Drive functions. Among others, there are main parameters of a file: ID, title/name, link, dates of creation and modification, author and last editor, file type and marker of a folder. File also may offer preview, editing and thumbnail links if its provider supports these features. There is also a flag `isSyncing()`, it is letting to know if a file is currently syncing (e.g. uploading to a cloud).

Cloud Drive core implements cloud file in `JCRLocalCloudFile` class. It is an implementation based on JCR backend of a cloud drive and it additionally introduces a method `getNode()` to access the file node. Its instances will be returned when getting a file from `CloudDrive` for example.
Interface `CloudFile` doesn't need to be implemented by a connector if there is not such requirement. But if such need happens, take in account that extension of the existing `JCRLocalCloudFile` may not work with eXo WS REST engine. 
For use in web-services layer the core already implemented `CloudFile` in `LinkedCloudFile`, to represent symlinks to files, and in `AcceptedCloudFile` for locally crated but not yet uploaded to cloud files.

Web services
============

Cloud Drive offers following kinds of RESTful web-services:
* `/clouddrive/connect` - a [service](https://github.com/exo-addons/cloud-drive-extension/blob/master/services/core/src/main/java/org/exoplatform/clouddrive/rest/ConnectService.java) to connect a new drive and control its connection progress (used by Javascript API for Documents menu)
* `/clouddrive/drive` - a [service](https://github.com/exo-addons/cloud-drive-extension/blob/master/services/core/src/main/java/org/exoplatform/clouddrive/rest/DriveService.java) to get an info about a cloud drive and its files (used by Javascript API in Documents views)
* `/clouddrive/features` - a [service](https://github.com/exo-addons/cloud-drive-extension/blob/master/services/core/src/main/java/org/exoplatform/clouddrive/rest/FeaturesService.java) providing discovery of Cloud Drive features (see more in Features API).

A connector may need, but not required, to provide additional services used by its UI or other clients or apps. It is recommended to keep all related service endpoints with a path `/clouddrive/drive/PROVIDER_ID` (e.g. /clouddrive/drive/cmis). This path may be used in future for connectors/features discovery.

Template project contains `SampleService` class which implements an abstract reading of file comments by a client script (`readComments()` in `cloudDrive-PROVIDER_ID.js` script). This sample RESTful service created from the template for "mycloud" provider:

```java
package org.exoplatform.clouddrive.mycloud.rest;
....

/**
 * Sample RESTful service to provide some specific features of "mycloud" connector.
 */
@Path("/clouddrive/drive/mycloud")
@Produces(MediaType.APPLICATION_JSON)
public class SampleService implements ResourceContainer {

  protected static final Log             LOG = ExoLogger.getLogger(SampleService.class);

  protected final CloudDriveFeatures     features;

  protected final CloudDriveService      cloudDrives;

  protected final RepositoryService      jcrService;

  protected final SessionProviderService sessionProviders;

  /**
   * Component constructor.
   * 
   * @param cloudDrives
   * @param features
   * @param jcrService
   * @param sessionProviders
   */
  public SampleService(CloudDriveService cloudDrives,
                         CloudDriveFeatures features,
                         RepositoryService jcrService,
                         SessionProviderService sessionProviders) {
    this.cloudDrives = cloudDrives;
    this.features = features;
    this.jcrService = jcrService;
    this.sessionProviders = sessionProviders;
  }

  /**
   * Return comments of a file from cloud side.
   * 
   * @param uriInfo
   * @param workspace
   * @param path
   * @param providerId
   * @return
   */
  @GET
  @Path("/comments/")
  @RolesAllowed("users")
  public Response getFileComments(@Context UriInfo uriInfo,
                                  @QueryParam("workspace") String workspace,
                                  @QueryParam("path") String path) {
    if (workspace != null) {
      if (path != null) {
        // TODO Get a cloud file and return collection of comments on the file.
        try {
          CloudDrive local = cloudDrives.findDrive(workspace, path);
          if (local != null) {
            List<Object> comments = new ArrayList<Object>();
            try {
              CloudFile file = local.getFile(path);
              // TODO fill collection of comments on the file.
              comments.add(new Object());
            } catch (NotCloudFileException e) {
              // we assume: not yet uploaded file has no remote comments
            }
            return Response.ok().entity(comments).build();
          }
          return Response.status(Status.NO_CONTENT).build();
        } catch (LoginException e) {
          LOG.warn("Error login to read drive file comments " + workspace + ":" + path + ": " + e.getMessage());
          return Response.status(Status.UNAUTHORIZED).entity("Authentication error.").build();
        } catch (CloudDriveException e) {
          LOG.warn("Error reading file comments " + workspace + ":" + path, e);
          return Response.status(Status.BAD_REQUEST).entity("Error reading file. " + e.getMessage()).build();
        } catch (RepositoryException e) {
          LOG.error("Error reading file comments " + workspace + ":" + path, e);
          return Response.status(Status.INTERNAL_SERVER_ERROR)
                         .entity("Error reading file comments: storage error.")
                         .build();
        } catch (Throwable e) {
          LOG.error("Error reading file comments " + workspace + ":" + path, e);
          return Response.status(Status.INTERNAL_SERVER_ERROR)
                         .entity("Error reading file comments: runtime error.")
                         .build();
        }
      } else {
        return Response.status(Status.BAD_REQUEST).entity("Null path.").build();
      }
    } else {
      return Response.status(Status.BAD_REQUEST).entity("Null workspace.").build();
    }
  }
}
```

And register this service in eXo container:
```xml
  <component>
    <type>org.exoplatform.clouddrive.mycloud.rest.SampleService</type>
  </component>
```

UI extensions
=============

Cloud Drive provides integration with Documents app via [UI extension](http://docs.exoplatform.com/PLF40/PLFRefGuide.PLFDevelopment.Extensions.UIExtensions.html) mechanism of eXo Platform.

Cloud Drive has following kinds of menu actions:
* Connect actions: a common dialog where user can chose which provider to connect and menu actions dedicated to some connector. The last, dedicated actions, is optional and it's up to a connector to provider them or not. Indeed it's simply to provide such support as need only extend a Java class `BaseConnectActionComponent` and use provider ID in it: in internal method and for the class name (see sample below). 
* Drive action to force synchronization or drive removal.
* File actions to open file embedded or in a new page on cloud site.

Note that minimal requirement for UI action to be properly uninstalled on the connector removal, it's implement `CloudDriveUIMenuAction` interface. All UI actions of this type will be correctly removed on the server stop and added back again on the start. This way the Cloud Drive can be safely uninstalled without causing ECMS UI damages.

A sample UI extension to connect a dedicated provider (Box here):
```java
@ComponentConfig(events = { @EventConfig(listeners = ConnectBoxActionComponent.ConnectBoxActionListener.class) })
public class ConnectBoxActionComponent extends BaseConnectActionComponent {

  /**
   * Box.com id from configuration - box.
   * */
  protected static final String PROVIDER_ID = "box";

  public static class ConnectBoxActionListener extends UIActionBarActionListener<ConnectBoxActionComponent> {

    public void processEvent(Event<ConnectBoxActionComponent> event) throws Exception {
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getProviderId() {
    return PROVIDER_ID; // return actual provider ID
  }
}
```

eXo ECMS uses menu action names and respectively related internationalization resources based on action class name. An example: for action class `ConnectBoxActionComponent`, configured as UI extension with name `ConnectBox`, the action name in resource is "ConnectBox" and name for Content administration action is "connectBox".

```xml
  <external-component-plugins>
    <target-component>org.exoplatform.webui.ext.UIExtensionManager</target-component>
    <component-plugin>
      <name>Add CloudDrive Actions</name>
      <set-method>registerUIExtensionPlugin</set-method>
      <type>org.exoplatform.webui.ext.UIExtensionPlugin</type>
      <init-params>
        <object-param>
          <name>ConnectBox</name>
          <object type="org.exoplatform.webui.ext.UIExtension">
            <field name="type">
              <string>org.exoplatform.ecm.dms.UIActionBar</string>
            </field>
            <field name="name">
              <string>ConnectBox</string>
            </field>
            <field name="component">
              <string>org.exoplatform.clouddrive.box.ecms.ConnectBoxActionComponent</string>
            </field>
            <field name="extendedFilters">
              <collection type="java.util.ArrayList">
                <value>
                  <object type="org.exoplatform.clouddrive.ecms.filters.PersonalDocumentsFilter"></object>
                </value>
              </collection>
            </field>
          </object>
        </object-param>
      </init-params>
    </component-plugin>
  </external-component-plugins>
```

Cloud Drive UI uses `CloudDriveContext` helper to initialize its UI components and Cloud Drive Javascript API. This helper adds Javascript code to initialize the client and setups available providers on the Documents app pages (via RequireJS support, see also below). When adding a new UI component or action, extend `BaseConnectActionComponent` and implement `getProviderId()` method, then this component will initialize context transparently. If not possible to extend, invoke `CloudDriveContext.init(portalRequestContext, workspace, nodePath)` on render phase (like in `renderEventURL()` method) of your component. If given workspace and node path belongs to some cloud drive, then the context will be initialized as described above.

Javascript API
==============

Cloud Drive comes with Javascript client module for integration in eXo Platform web applications. Client provides support of drive connection and synchronization in user interface. It also customizes Documents client app to render cloud drive properly: drive and files styling, context and action bar menus, embedded file preview and editing. 

Cloud Drive client loads its Javascript using [RequireJS](requirejs.org/docs/api.html) framework integrated in [eXo Platform](http://docs.exoplatform.com/PLF40/sect-Reference_Guide-Javascript_Development-JavaScript_In_GateIn-GMD_Declaring_Module.html). The client it is an [AMD module](http://en.wikipedia.org/wiki/Asynchronous_module_definition) with name `cloudDrive`, it will be loaded when a connector will initialize its provider on the Documents app page (via UI extension context). The module itself will load requires resources for drive providers (CSS, Javascript module etc.). Module can provide a special method for implicit on-load initialization: a method `onLoad(provider)`, if exisists, will be invoked by the client on the provider initialization for page or its gragment loading (see example code below).

Javascript API pluggable and specific logic can be provided by a connector to invoke synchronization on drive state change. When client module initialized it starts automatic synchrinization and check if a connector module exists for a drve provider. Connector module name should have a name in form of `cloudDrive.PROVIDER_ID` to be loaded by the client automaticaly. If module loaded successfully, then this module can provide asynchronous invokation on the drive state change (see "Drive state monitoring" below). 

Connector module, as any other scripts, can use `cloudDrive` as AMD dependency. Most of Cloud Drive methods return [jQuery Promise](http://api.jquery.com/deferred.promise/) object which can be used for callbacks registration for connect or synchronization operations. 

There are also `cloudDriveUtils` module which offers cookies management, CSS loading and unified logger. Refer to source code of modules for more information.

It is an example how a connector module can looks (simplified Template connector for My Cloud sample):
```javascript
/**
 * My Cloud support for eXo Cloud Drive.
 */
(function($, cloudDrive, utils) {

	/**
	 * My Cloud connector class.
	 */
	function MyCloud() {
		// Provider Id for Template provider
		var PROVIDER_ID = "mycloud";

		var prefixUrl = utils.pageBaseUrl(location);

		/**
		 * Renew drive state object.
		 */
		var renewState = function(process, drive) {
			var newState = cloudDrive.getState(drive);
			newState.done(function(res) {
				drive.state = res;
				process.resolve(); // this will cause sync also
			});
			newState.fail(function(response, status, err) {
				process.reject("Error getting drive state. " + err + " (" + status + ")");
			});
			return newState;
		};

    /**
     * Initialize this module on its load to the page (or page updated within a fragment). 
     * This method will be invoked by Cloud Drive client on a page or its fragment loading to the browser.
     */
    this.onLoad = function(provider) {
			$(function() {
				// Add an action to some button "Show File Comments"
				$("#file_comments_button").click(function() {
					var drive = cloudDrive.getContextDrive();
					var file = cloudDrive.getContextFile();
					if (drive && file) {
						var comments = client.readComments(drive.workspace, file.path);
						comments.done(function(commentsArray, status) {
							if (status != 204) { // NO CONTENT means no drive found or drive not connected
								// Append comments to a some invisible div on the page and then show it
								var container = $("#file_comments_div");
								$.each(commentsArray, function(i, c) {
									$("<div class='myCloudFileComment'>" + c + "</div>").appendTo(container);
								});
								container.show();
							} // else do nothing
						});
						comments.fail(function(response, status, err) {
							utils.log("Comments request failed. " + err + " (" + status + ") " + JSON.stringify(response));
						});
					}
				});
			});
    });
      
		/**
		 * Check if given drive has remote changes. Return jQuery Promise object that will be resolved
		 * when some change will appear, or rejected on error. It is a method that Cloud Drive core
		 * client will look for when loading the connector module.
		 * 
		 * Thanks to use of jQuery Promise drive state synchronization runs asynchronously, only when an
		 * actual change will happen in the drive.
		 */
		this.onChange = function(drive) {
			var process = $.Deferred();

			if (drive) {
				if (drive.state) {
					// Drive supports state - thus we can send connector specific data via it from Java API
					// State it is a POJO in JavaAPI. Here it is a JSON object.
					// For an example here we assume that cloud provider has events long-polling service that
					// return OK when changes happen (this logic based on Box connector client - replace
					// response content and logic according your cloud service).
					var changes = cloudDrive.ajaxGet(drive.state.url);
					changes.done(function(info, status) {
						if (info.message == "new_change") {
							process.resolve();
						} else if (info.message == "reconnect") {
							renewState(process, drive);
						}
					});
					changes.fail(function(response, status, err) {
				    process.reject("Changes request failed. " + err + " (" + status + ") "
						        + JSON.stringify(response));
			    });
				} else {
					process.reject("Cannot check for changes. No state object for Cloud Drive on " + drive.path);
				}
			} else {
				process.reject("Null drive in onChange()");
			}

			return process.promise();
		};

		/**
		 * Read comments of a file. This method returns jQuery Promise of the asynchronous request.
		 */
		this.fileComments = function(workspace, path) {
			return cloudDrive.ajaxGet(prefixUrl + "/portal/rest/clouddrive/drive/mycloud", {
			  "workspace" : workspace,
			  "path" : path
			});
		};
	}

	var client = new TemplateClient();

	// apply per-app customization (e.g. load global styles or images), see also onLoad() above
	if (window == top) { // run only in window (not in iframe as gadgets may do)
	  try {
			// load custom/parties style
			utils.loadStyle("/cloud-drive-mycloud/skin/thirparty-style.css");
		} catch(e) {
			utils.log("Error intializing mycloud client.", e);
		}
	}

	return client;
})($, cloudDrive, cloudDriveUtils);
```

Drive state monitoring
----------------------

It's possible to manage how the Cloud Drive client will invoke drive update (synchronization and related opeations). When cloud drive folder is open by an user, the client runs automatic synchronization of the drive. By default client will run periodic synchronization starting from syncing each 20 seconds for 30 minutes period (after 10 minututes it will sync every 40 seconds). It is an independent and working but not efficient way. If cloud service can provide an event based way of getting a drive state, it's better to use it.

If connector provides a Javascript module and Cloud Drive client can load it, it will check if returned module has a method `onChange()` and if it does, then object returned by this method will be used as an initiator for drive synchronization. If method doesn't exists then default behaviour will be used. 

Cloud Drive client expects that connector's `onChange()` method returns [jQuery Promise](http://api.jquery.com/deferred.promise/) and then it uses the promise to initiate synchronization process. This logic shown in the connector module code above. When the promise will be resolved by the connector (its `.resolve()` invoked), it will mean the drive has new remote changes and client will start synchronization and then refresh the UI. If promise rejected (`.reject(error)` invoked) then automatic synchronization will be canceled and a new synchronization will be attempted when an user perform an operation on the Documents page (navigate to other file or folder, use Cloud Drive menu). Thanks to use of jQuery Promise drive state synchronization runs asynchronously, only when an actual change will happen in the drive.

Deploy to eXo Platform
======================

To be successfully deployed to eXo Platform, Cloud Drive connector should be packaged as a [portal extension](http://docs.exoplatform.com/PLF40/PLFDevGuide.eXoPlatformExtensions.html), a zip archive containing following files:
* services JAR: exo-clouddrive-PROVIDER\_ID-services-VERSION.jar,
* web app WAR: cloud-drive-PROVIDER\_ID.war 

Where PROVIDER\_ID is for actual provider ID and VERSION for the connector version.

Use eXo Platform [Add-ons Manager](https://github.com/exoplatform/addons-manager) or older Extensions manager script to install a connector. Changes of _configuration.properties_ should be performed manually by a person who administrate the server.

A [_template_ connector](https://github.com/exo-addons/cloud-drive-extension/tree/master/connectors/template) project already contains Maven modules with Cloud Drive dependencies and packaging. Copy template sub-folder to your new location and replace `PROVIDER_ID`, `PROVIDER_NAME` and `ConnectTemplate` with actual values in source files and configuration, rename respectively package, class names and variables: e.g. `cmis`, `CMIS` and `ConnectCMIS`. Fill the sources with a logic to work with your connector cloud services. Add required third-party libraries to the Maven dependencies and assembly of the packaging. Then build the connector and use its packaging artifact as a connector extension.









