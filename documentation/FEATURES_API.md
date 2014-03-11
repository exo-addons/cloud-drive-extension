eXo Cloud Drive Features API
============================

Since version 1.1.-Beta05, eXo Cloud Drive provides an oportunity to control its operations via Features API. This API is very simple and consists of two parts: a component, specifying actual control on the features, and a RESTful service for discovering of this control rules. The component, it implements simple Java interface `org.exoplatform.clouddrive.features.CloudDriveFeatures`, designed in style of [Specification Patter](http://en.wikipedia.org/wiki/Specification_pattern), its methods answer on questions about granting of features to a given user. The REST service, it is read only, requires user authentication, it offers an access to specified rules from outside the Java apps.  

Following features are under control:
* Possibility to connect a drive by an user
* Automatic synchronization when an user is working in his drive folder in Documents app

Configuration
=============

By default all features permitted to all users. These rules implemented in default implementation of `CloudDriveFeatures`, in component `org.exoplatform.clouddrive.features.PermissiveFeatures`. This component already added to the configuration in `clouddrive.war`.
Implement another component when need apply other rules for the extension. Reguster this new component in top extension with the component key:

```xml
  <component>
    <key>org.exoplatform.clouddrive.CloudDriveFeatures</key>
    <type>com.acme.clouddrive.ControlledFeatures</type>
  </component>
```

Rule new drives
===============

When an user will request a new drive creation, calling `CloudDriveService.createDrive()`, the service will call `CloudDriveFeatures.canCreateDrive()` with current JCR workspace and node path, with user id and, optionally, with `CloudProvider` of the request. If the features allows the creation, this method should return `true` result, otherwise it returns `false` or alternatively may throw `CannotCreateDriveException`. By default all drive requests are allowed.

```java
  @Override
  public boolean canCreateDrive(String workspace, String nodePath, String userId, CloudProvider provider) {
    // TODO decide for a new drive creation here, an example logic below:
    return acmeOrganization.hasUserRights(userId, workspace, nodePath) && allowedProviders.contains(provider);
  }
```

This method also used for a default UI implementation for Platform Documents. If a drive cannot be created then a request context in portal, class `CloudDriveContext`, will not be initialized with Cloud Drive settings and finally the UI script will not be able to initiate a new drive creation by the user - user operation will have no effect.
To provide much clearer user experience need implement dedicated UI forms in case of limited drive creation.

Drive creation rule also exposed via REST service.

```
GET /portal/rest/clouddrive/features/can-create-drive?workspace=NAME&path=PATH&provider=ID
```

Rule auto-synchronization
=========================

Automatic synchronization it is fully an UI feature, it doesn't have any special logic on server side. When an user opens his drive folder in Documents app, the app script will check can the user use the automatic synchronization, and if user can - a special client process start to monitor the drive for remote changes, this will work while the user is working in the drive folders. This process is connected to the Documents UI and when the user will leave the drive folder, the process will stop. The process consumes the REST service to get a status of the autosync for current drive. By default automatic synchronization allowed.

```java
  @Override
  public boolean isAutosyncEnabled(CloudDrive drive) {
    // TODO decide for automatic synchronization for given drive, an example logic below:
    return acmeOrganization.isPremiumUser(drive.getUser().getId());
  }
 
Auto-synchronization rule check exposed via REST service. It used by default UI implementation.

```
GET /portal/rest/clouddrive/features/is-autosync-enabled?workspace=NAME&path=PATH
```

