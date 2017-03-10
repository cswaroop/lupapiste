LUPAPISTE.LoginModel = function( params ) {
  "use strict";
  var self = this;

  ko.utils.extend( self, new LUPAPISTE.ComponentBaseModel());

  self.username = ko.observable( ko.unwrap( params.username ) || "" );
  self.password = ko.observable();
  self.error = ko.observable();
  var callback = params.callback || _.noop;

  self.canLogin = self.disposedComputed( function() {
    var can = _.trim(self.username()) && self.password();
    if( can ) {
      self.error( "" );
    }
    return can;
  });
  self.login = function() {
    ajax.command( "login", {username: _.trim( self.username()),
                          password: self.password()})
    .success( function( res ) {
      hub.send( "login", res );
      callback();
    })
    .error( function( res ) {
      self.error( res.text );
    })
    .call();
  };
};
