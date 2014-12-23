/**
 * Box support for eXo Cloud Drive.
 */
(function($, cloudDrive, utils) {

	/**
	 * Box connector class.
	 */
	function Box() {
		// Provider Id for Box
		var PROVIDER_ID = "box";

		var renewState = function(process, drive) {
			var newState = cloudDrive.getState(drive);
			newState.done(function(res) {
				//utils.log(">>> new changes link: " + res.url);
				drive.state = res;
				// this will cause sync also
				process.resolve();
			});
			newState.fail(function(response, status, err) {
				process.reject("Error getting new changes link. " + err + " (" + status + ")");
			});
			return newState;
		};

		/**
		 * Check if given Box drive has remote changes. Return jQuery Promise object that will be resolved when
		 * some change will appear, or rejected on error.
		 */
		this.onChange = function(drive) {
			var process = $.Deferred();

			if (drive) {
				//utils.log(">>> enabling long-polling changes monitor for Cloud Drive " + drive.path);

				if (drive.state) {
					// use events long-polling from Box
					var nowTime = new Date().getTime();
					var linkAge = nowTime - drive.state.created;
					if (linkAge >= drive.state.outdatedTimeout) {
						// long-polling outdated - renew it (will cause immediate sync after that)
						//utils.log(">>> changes link already outdated " + linkAge + ">=" + drive.state.outdatedTimeout);
						renewState(process, drive);
					} else {
						var linkLive;
						var changes = cloudDrive.ajaxGet(drive.state.url);
						changes.done(function(info, status) {
							//utils.log(">>> changes done " + JSON.stringify(info) + " " + status);
							clearTimeout(linkLive);
							if (info.message) {
								// http://developers.box.com/using-long-polling-to-monitor-events/
								if (info.message == "new_change") {
									process.resolve();
								} else if (info.message == "reconnect") {
									renewState(process, drive);
								}
							}
						});
						changes.fail(function(response, status, err) {
							clearTimeout(linkLive);
							//utils.log(">>> changes fail " + JSON.stringify(response) + " " + status + " " + err);
							if (err != "abort") {// if not aborted by linkLive timer or browser
								if (( typeof err === "string" && err.indexOf("max_retries") >= 0) 
										|| (response && response.err && response.error.indexOf("max_retries") >= 0)) {
									// need reconnect
									renewState(process, drive);
								} else {
									process.reject("Long-polling changes request failed. " + err + " (" + status + ") " + JSON.stringify(response));
								}
							}
						});
						// long-polling can outdate, if request runs longer of the period - need start a new one
						linkLive = setTimeout(function() {
							//utils.log(">>> long-polling link outdated, renewing it...");
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
	}

	return new Box();

})($, cloudDrive, cloudDriveUtils);
