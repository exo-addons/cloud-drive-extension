/**
 * CMIS support for eXo Cloud Drive.
 * 
 */
(function($, cloudDrive, utils) {

	// On DOM-ready handler to initialize custom UI (or any other specific work on a client)
	if (window == top) { // run only in window (not in iframe as gadgets may do)
		try {
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
		} catch(e) {
			utils.log("Error intializing Template client.", e);
		}
	}

	return {}; // empty client
})($, cloudDrive, cloudDriveUtils);
