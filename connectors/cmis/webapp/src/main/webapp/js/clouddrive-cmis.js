/**
 * CMIS support for eXo Cloud Drive.
 * 
 */
(function($, cloudDrive, utils, codeMirror) {

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
				var $viewer = $("#WebContent");
				var $code = $viewer.find("#TabCode");
				var $codeSwitch = $("#FileCodeSwitch");
				$codeSwitch.click(function() {
					if ($code.size() > 0 && $code.children().size() == 0) {
						var codeURL = $viewer.find("iframe").attr("src");
						var cursorCss = $viewer.css("cursor");
						$viewer.css("cursor", "wait");
						$.get(codeURL, function(data, status, jqXHR) {
							try {
								var code = jqXHR.responseText;
								var contentType = jqXHR.getResponseHeader("Content-Type");
								if (!contentType) {
									contentType = "htmlmixed";
								}
								var myCodeMirror = CodeMirror($code.get(0), {
								  value : code,
								  lineNumbers : true,
								  readOnly : true,
								  mode : contentType
								});
							} catch(e) {
								utils.log("ERROR: CodeMirror creaton error " + provider.name + "(" + provider.id + "). " 
										+ e.message + ": " + JSON.stringify(e));
							} finally {
								$viewer.css("cursor", cursorCss);
							}
						});
					}
				});
				
				if ($code.is(".active")) {
					// it is XML document, click to load its content
					$codeSwitch.click();
					$viewer.find("#TabHTML").css("display", "");
				}
				
				// XXX remove display: block, it appears for unknown reason
				$code.css("display", "");
			});
		};
	}

	if (window == top) { // run only in window (not in iframe as gadgets may do)
		try {
			// load codemirror styles
			utils.loadStyle("/cloud-drive-cmis/skin/codemirror.css");
		} catch(e) {
			utils.log("Error intializing CMIS client.", e);
		}
	}

	return new CMIS();
})($, cloudDrive, cloudDriveUtils, codeMirror);
