eXo Cloud Drive Connector Template
==================================

It is a template project for Cloud Drive connector development. It consist of three modules: services, webapp and packaging. First and second it's portal extension parts as described in [Connector API](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md), the packaging is for deployment according the API documentation.

This template connector is a Maven project with Cloud Drive dependencies and packaging. Copy the template sub-folder to your new location and replace `PROVIDER_ID`, `PROVIDER_NAME` and `ConnectTemplate` with actual values in source files and configuration, rename respectively packages, class names and variables: e.g. by `cmis`, `CMIS` and `ConnectCMIS`. Fill the sources with a logic to work with your connector cloud services. Then build the connector and use its packaging artifact as a connector extension. For further details refer to the [Connector API documentation](https://github.com/exo-addons/cloud-drive-extension/blob/master/documentation/CONNECTOR_API.md).



