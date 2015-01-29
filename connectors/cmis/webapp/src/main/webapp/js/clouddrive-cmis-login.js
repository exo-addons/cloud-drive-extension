/**
 * CMIS connector login support for eXo Cloud Drive.
 *
 */
(function($) {

	/** 
	 * http://stackoverflow.com/questions/901115/how-can-i-get-query-string-values-in-javascript
	 * */
	var urlParam = function(name, url) {
		if (!url) {
			url = window.location.href;
		}
		var results = new RegExp('[\\?&]' + name + '=([^&#]*)').exec(url);
		if (!results) {
			return undefined;
		}
		return results[1] || undefined;
	};

	$(function() {
		var providerId = urlParam("providerId");
		
		// hide PLF admin toolbar items
		$("#UIToolbarContainer div.UIContainer").toggle("fade");

		// setup validator
		var $message = $("#requiredMessage");
		var validator = $("#cmis-login-data, #cmis-login-repository").validate({
			focusInvalid : false,
			errorClass : "error",
			validClass : "success",
			rules : {
				"service-url" : {
					required : true,
					url : true
				},
				"user" : {
					required : true
				},
				"password" : {
					required : true
				},
				"repository" : {
					required : true
				}
			},
			showErrors : function(errorMap, errorList) {
				// reset all error labels before
				$("#cmis-login-data, #cmis-login-repository").find(".control-group").removeClass("error");
				// and set error for not valid only
				for (var i = 0; i < errorList.length; i++) {
					$(errorList[i].element).parent().parent().addClass("error");
				}
				// show default labels (validator work)
				this.defaultShowErrors();
			}/* TODO more detailed validation message?
			 * , invalidHandler : function(event, validator) { // show global warning on the form var
			 * errors = validator.numberOfInvalids(); if (errors) { $message.show(); $("html,
			 * body").animate({ scrollTop : $message.offset().top }, 200); } else { $message.hide(); } }
			 */
		});

		var $loginData = $("#cmis-login-data");
		var $login = $("#cmis-login-form");
		var $error = $("#cmis-login-error div.alert-warning");

		$("#service-url-predefined a").click(function() {
			$loginData.find(":input[name='service-url']").val($(this).attr("data-url"));
		});

		$loginData.find("input").keypress(function(event) {
			if (event.which == 13) {
				event.preventDefault();
				$error.empty();
				$loginData.submit();
			}
		});

		$login.submit(function() {
			$login.find("button.btn.btn-primary").attr("disabled", "disabled");
		});
		$login.find("select").keypress(function(event) {
			if (event.which == 13) {
				event.preventDefault();
				$login.submit();
			}
		});

		$loginData.submit(function(event) {
			event.preventDefault();
			var authValid = $loginData.valid();
			if (authValid) {
				var serviceURL = $loginData.find("input[name='service-url']").val();
				var user = $loginData.find("input[name='user']").val();
				var password = $loginData.find("input[name='password']").val();
				$("#cmis-login-key").jzLoad("CMISLoginController.userKey()", {
					"userName" : user
				}, function(response, status, jqXHR) {
					// complete callback
					// console.log(JSON.stringify(response));
					var key = $("#cmis-login-key span").attr("data-key");
					if (key) {
						console.log("key: " + key);
						var $repository = $("#cmis-login-repository");
						var cursorCss = $loginData.css("cursor");
						$loginData.css("cursor", "wait");
						$loginData.find("button.btn.btn-primary").attr("disabled", "disabled");
						// TODO encrypt user and password
						$repository.jzLoad("CMISLoginController.loginUser()", {
							"serviceURL" : serviceURL,
							"userName" : user,
							"password" : password,
							"providerId" : providerId
						}, function(response, status, jqXHR) {
							$loginData.css("cursor", cursorCss);
							$loginData.find("button.btn.btn-primary").removeAttr("disabled");
							if (status == "error") {
								var message = jqXHR.statusText + " (" + jqXHR.status + ")";
								console.log("ERROR: submit failed " + message + ". " + jqXHR.responseText);
								$error.empty();
								$("<i class='uiIconError'></i><span>" + message + "</span>").appendTo($error);
								$error.show();
							} else {
								var code = $("#cmis-login-code").attr("user-code");
								if (code) {
									console.log("code: " + code);
									$error.empty();
									$login.find("input[name='code']").val(code);
									var params = window.location.search;
									if (params.length > 0) {
										if (params.indexOf("?") == 0) {
											params = params.substring(1);
										}
										$login.attr("action", $login.attr("action") + "&" + params);
									} else {
										console.log("No additional form params");
									}
									var $option = $repository.find("select>option");
									if ($option.length == 1) {
										// if only single repository available - submit automatically
										$option.parent().val($option.val());
										$login.submit();
									} else {
										// toggle to the repository form
										$loginData.toggle("blind");
										$login.show();
									}
								} else {
									var $message = $repository.find(".message-text");
									if ($message.length > 0) {
										console.log($message.text());
										$error.empty();
										$message.detach().appendTo($error);
										$error.show();
									} else {
										console.log("WARN: code not found");
									}
								}
							}
						});
					} else {
						console.log("WARN: user key not found");
					}
				});
			} // else form not valid
		});
	});

	return {};
})($);
