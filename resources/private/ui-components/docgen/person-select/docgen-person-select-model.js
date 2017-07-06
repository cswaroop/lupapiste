LUPAPISTE.DocgenPersonSelectModel = function( params ) {
  "use strict";
  var self = this;

  ko.utils.extend( self, new LUPAPISTE.DocgenInputModel( params ));

  // selectValue is a proxy for the underlying document data service
  // value. This approach allows for canceling.
  self.selectValue = ko.observable( self.value() );
  var partiesModel = _.get(params, "docModel.partiesModel");
  self.appId = partiesModel.applicationId;
  self.myNs = self.path.slice(0, self.path.length - 1).join(".");

  var collectionFn = _.get(params, "docModel.getCollection") || function() { return "documents"; };
  var collection = collectionFn();

  self.personOptions = self.disposedComputed( function() {
    var itemsObservable = _.get(partiesModel, "personSelectorItems");
    return itemsObservable ? itemsObservable() : [];
  });

  self.optionsText = function( person ) {
    if (person.id) {
      var name = person.lastName + " " + person.firstName;
      if (person.company) {
        return name + ", " + person.company.name;
      } else {
        return name;
      }
    }
  };

  self.setUserToDocumentOk = ko.observable(false);
  if (self.authModel.ok("set-user-to-document")) {
    self.disposedSubscribe(self.selectValue, function(value) {
      if (value !== self.value()) {
        if (!_.isEmpty(value)) {
          ajax.command("set-user-to-document", { id: self.appId, documentId: self.documentId, userId: value, path: self.myNs, collection: collection })
            .success( _.partial( repository.load, self.appId, _.noop))
            .error(function(e) {
              if (e.text !== "error.application-does-not-have-given-auth") {
                error("Failed to set user to document", value, self.docId, e);
              }
              notify.ajaxError(e);
            })
            .call();
        }
      }
    });
    self.setUserToDocumentOk(true);
  }

  self.inviteWithRoleOk = ko.observable(false);
  if (self.authModel.ok("invite-with-role")) {
    self.inviteWithRoleOk(true);
  }

  self.invite = function() {
    $("#invite-document-name").val(self.schemaName).change();
    $("#invite-document-path").val(self.myNs).change();
    $("#invite-document-id").val(self.documentId).change();
    LUPAPISTE.ModalDialog.open("#dialog-valtuutus");
  };

};