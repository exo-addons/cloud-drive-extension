/**
 * CMIS connector login support for eXo Cloud Drive.
 * 
 */
(function(jzAjax, $) {

	function LoginClient() {

		this.getUserKey = function(user) {

		};
	}

	var client = new LoginClient();

	$(function() {
		$("#cmis-login-form").click(function() {
			var user = $(this).find(":input[name='user']").val();
			if (user) {
				$("#cmis-login-user").jzLoad("CMISLoginController.userKey()", {
					"user" : user
				}, function(response, status, jqXHR) {
					// complete callback
					var key = $("#cmis-login-user.span").attr("data-key");
					console.log(key);
				});
			}
		});
	});

	return client;
})(jzAjax, $);
