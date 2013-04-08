eXo Cloud Drive extension
=========================

eXo Platform portal extension providing integration with cloud drives such as Google Drive.

Thanks to this extension users can expose their cloud files to eXo Platform apps. It has UI based on WCM Document Explorer and connects cloud drives as a folder in JCR repository.

Currently supported cloud drives:
* Google Drive

Configuration :


In Google Console, 
Activate Drive API
Create a new Client id for web application.
Set authoriezd Redirect api like this :
https://yourServerName{:port}/portal/rest/clouddrive/connect/gdrive
Set Autorized Javascript origins like this :
http://yourServerName{:port}
Validate the form.

Some system properties are needed for make this extension working. Theses properties can be setted in start command or in file configuration.properties :

clouddrive.google.client.id=xxx.apps.googleusercontent.com
clouddrive.google.client.secret=secretKeyProvidedByGoogleConsole
clouddrive.service.host=yourServerName{:port}
#If you are in multitenant mode, set this property. Else, comment it.
tenant.masterhost=yourMasterHost


