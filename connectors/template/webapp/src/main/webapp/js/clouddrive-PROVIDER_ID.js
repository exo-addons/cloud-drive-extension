/**
 * PROVIDER_ID support for eXo Cloud Drive.
 * 
 * TODO Replace PROVIDER_ID with actual value.
 * 
 * TODO It is "almost" dummy code for the Javascript client. Fill it with real logic following the
 * ideas below and your cloud service API.
 */
(function($, cloudDrive, utils) {

	/** 
	 * PROVIDER_ID connector class. 
	 */ 
  function TemplateClient() {
		// Provider Id for Template provider
		var PROVIDER_ID = "YOUR PROVIDER_ID";

		var prefixUrl = utils.pageBaseUrl(location);

		/**
		 * Renew drive state object.
		 */
		var renewState = function(process, drive) {
			var newState = cloudDrive.getState(drive);
			newState.done(function(res) {
				drive.state = res;
				// this will cause sync also
				process.resolve();
			});
			newState.fail(function(response, status, err) {
				process.reject("Error getting drive state. " + err + " (" + status + ")");
			});
			return newState;
		};

		/**
		 * Check if given drive has remote changes. Return jQuery Promise object that will be resolved
		 * when some change will appear, or rejected on error. It is a method that Cloud Drive core
		 * client will look for when loading the connector module.
		 * 
		 * Cloud Drive client expects that connector's `onChange()` method returns jQuery Promise and
		 * then it uses the promise to initiate synchronization process. When the promise will be
		 * resolved (its `.resolve()` invoked), it will mean the drive has new remote changes and client
		 * will invoke the synchronization web-service and then refresh the UI. If promise rejected
		 * (`.reject(error)` invoked) then the synchronization will be canceled, and a new
		 * synchronization will be attempted when an user perform an operation on the Documents page
		 * (navigate to other file or folder, use Cloud Drive menu).
		 * 
		 * Thanks to use of jQuery Promise drive state synchronization runs asynchronously, only when an
		 * actual change will happen in the drive.
		 */
		this.onChange = function(drive) {
			var process = $.Deferred();

			if (drive) {
				// utils.log(">>> enabling changes monitor for Cloud Drive " + drive.path);

				if (drive.state) {
					// Drive supports state - thus we can send connector specific data via it from Java API
					// State it is a POJO in JavaAPI. Here it is a JSON object.

					// For an example here we assume that cloud provider has events long-polling service that
					// return OK when changes happen (this logic based on Box connector client - replace
					// response content and logic according your cloud service).
					var nowTime = new Date().getTime();
					var linkAge = nowTime - drive.state.created;
					if (linkAge >= drive.state.outdatedTimeout) {
						// long-polling outdated - renew it (will cause immediate sync after that)
						renewState(process, drive);
					} else {
						var linkLive;
						var changes = cloudDrive.ajaxGet(drive.state.url);
						changes.done(function(info, status) {
							clearTimeout(linkLive);
							if (info.message) {
								if (info.message == "new_change") {
									process.resolve();
								} else if (info.message == "reconnect") {
									renewState(process, drive);
								}
							}
						});
						changes.fail(function(response, status, err) {
					    clearTimeout(linkLive);
					    // if not aborted by linkLive timer or browser
					    if (err != "abort") {
						    if ((typeof err === "string" && err.indexOf("max_retries") >= 0)
						        || (response && response.error.indexOf("max_retries") >= 0)) {
							    // need reconnect
							    renewState(process, drive);
						    } else {
							    process.reject("Long-polling changes request failed. " + err + " (" + status + ") "
							        + JSON.stringify(response));
						    }
					    }
				    });
						// long-polling can outdate, if request runs longer of the period - need start a new one
						linkLive = setTimeout(function() {
							changes.request.abort();
							renewState(process, drive);
						}, drive.state.outdatedTimeout - linkAge);
					}
				} else {
					process.reject("Cannot check for changes. No state object for Cloud Drive on " + drive.path);
				}
			} else {
				process.reject("Null drive in onChange()");
			}

			return process.promise();
		};
		
		/**
     * Initialize provider client in context of current file.
     */
    this.initContext = function(provider) {
      $(function() {
        // For example: we want use preview link as download link for not viewable files
        var file = cloudDrive.getContextFile();
        if (file) {
          var $target = $(".PROVIDER_ID_preview_class");
          $target.attr("href", file.previewLink);
        }
      });
    };
    
    /**
     * Init provider's connected drive in the page context. This method will be called when drive initializes 
     * on the page by cloudDrive.initContext() method, thus once per page load. 
     */
    this.initDrive = function(drive) {
      $(function() {
        // Here we can customize the drive object, or how this provider drive looks (CSS etc) or 
        // add extra logic (Javascript) to its controls. 
        // It's also possible to perform any other related custom operations on this phase. 
      });
    };

    /**
     * Init provider's connected file in the page context. This method will be called in following cases: 
     * 1) when drive initializes on the page by cloudDrive.initContext() method (once per page load for each file existing at this moment); 
     * 2) on cloudDrive.synchronize() for each affected file;
     * 3) on file lazy reading and by the cloudDrive.getFile() or cloudDrive.getContextFile()
     */
    this.initFile = function(file) {
      $(function() {
        // Here we can customize this file object and/or how this file looks (CSS etc) or 
        // add extra logic (Javascript) to its controls.
        // Any other operations can be possible here, but take in account that this method may be 
        // called many times and heavy calculations here may affect the performance of the client.
      });
    };
    
    /**
     * IT IS A CUSTOM METHOD OF THE CLIENT
     * For internal use, see below in DOM-ready handler.
		 * Read comments of a file. This method returns jQuery Promise of the asynchronous request.
		 * It's fake method of the client to show how specific methods can be used.
		 */
		this.myFileComments = function(workspace, path) {
		  // This method only to show how API can be used, no such REST endpoint exists so far 
			return cloudDrive.ajaxGet(prefixUrl + "/portal/rest/myservice/filecomments", {
			  "workspace" : workspace,
			  "path" : path
			});
		};
	}

	var client = new TemplateClient();

	// On DOM-ready handler to initialize custom UI (or any other specific work on a client)
	if (window == top) { // run only in window (not in iframe as gadgets may do)
		try {
			$(function() {
				// Add an action to some button "Show File Comments"
				$("#file_comments_button").click(function() {
					var drive = cloudDrive.getContextDrive();
					var file = cloudDrive.getContextFile();
					if (drive && file) {
						var comments = client.myFileComments(drive.workspace, file.path);
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
		} catch(e) {
			utils.log("Error intializing Template client.", e);
		}
	}

	return client;
})($, cloudDrive, cloudDriveUtils);
