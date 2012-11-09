/*
 * authorization.js:
 */

var authorization = function() {

  function AuthorizationModel() {
    var self = this;

    self.data = ko.observable({})

    self.ok = function(command) {
      return self.data && self.data()[command] && self.data()[command].ok;
    }

    self.refresh = function(application, callback) {
      ajax.query("allowed-actions", {id: application.id})
        .success(function(d) {
          self.data(d.actions);
          if (callback) callback();
        })
        .call();
    }
  }

  return {
    create: function() {return new AuthorizationModel(); }
  };

}();
