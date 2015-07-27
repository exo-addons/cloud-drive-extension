/**
 * CMIS support for eXo Cloud Drive.
 *
 */
(function($, cloudDrive, utils) {

	/**
	 * CMIS connector class.
	 */
	function CMIS() {

		/**
		 * Initialize CMIS client on its load on the page (or the page fragment update).
		 */
		this.onLoad = function(provider) {
			// Add an action to "show plain text" tab to load the frame content to the code viewer
			$(function() {
				cloudDrive.initTextViewer();
			});
		};
	}

	if (window == top) {// run only in window (not in iframe as gadgets may do)
		try {
			// load codemirror styles
			utils.loadStyle("/cloud-drive/skin/codemirror.css");
		} catch(e) {
			utils.log("Error intializing CMIS client.", e);
		}
	}

	return new CMIS();
})($, cloudDrive, cloudDriveUtils);
