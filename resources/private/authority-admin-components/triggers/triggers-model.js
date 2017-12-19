LUPAPISTE.TriggersModel = function( params ) {
  "use strict";
  var self = this;

  var triggerService        = lupapisteApp.services.triggerService;
  var handlerService        = lupapisteApp.services.handlerService;
  var triggersTargetService = lupapisteApp.services.triggersTargetService;

  self.triggers = triggerService.organizationAssignmentTriggers( params.organization );
  var roles = handlerService.organizationHandlerRoles( params.organization );

  self.selectableRoles = ko.pureComputed(function() {
    return _.filter(roles(), _.negate(handlerService.isTemporaryRole));
  });

  self.canEdit = triggerService.canEdit;

  function wrapInObject(types) {
    return _.map(types, function(type) {
      return {
        title: loc(["attachmentType", type].join(".")),
        "type-group": type.split(".")[0],
        "type-id": type.split(".")[1]};
    });
  }

  function DialogData() {
    var dd = this;
    dd.id = ko.observable();
    dd.target = ko.observable();
    dd.handler = ko.observable();
    dd.description = ko.observable();

    dd.reset = function() {
      dd.id = null;
      triggersTargetService.selected([]);
      dd.handler("");
      dd.description("");
    };

    dd.loadData = function(trigger) {
      dd.id = trigger.id;
      dd.target(trigger.targets);
      lupapisteApp.services.triggersTargetService.selected(wrapInObject(trigger.targets));
      if (trigger.handlerRole !== undefined && trigger.handlerRole !== null) {
        var selectedRole = _.find(self.selectableRoles(), function(role) { return role.id() === trigger.handlerRole.id; });
        dd.handler(selectedRole);
      }
      dd.description(trigger.description);
    };

    dd.isGood = ko.pureComputed( function() {
      var selectedType = ko.unwrap(lupapisteApp.services.triggersTargetService.selected);
      return (selectedType !== undefined) && dd.description() && triggersTargetService.selected().length > 0;
    });

    dd.saveTrigger = function() {
      triggerService.addAssignmentTrigger(dd);
    };
  }

  self.dialogData = new DialogData();

  self.addTrigger = function() {
    self.dialogData.reset();
    LUPAPISTE.ModalDialog.open( "#dialog-add-trigger");
  };

  self.editTrigger = function(trigger) {
    self.dialogData.loadData(trigger);
    LUPAPISTE.ModalDialog.open( "#dialog-add-trigger");
  };

  self.removeTrigger = function(trigger) {
    LUPAPISTE.ModalDialog.showDynamicYesNo(
      loc("triggers.confirm.remove.title"),
      loc("triggers.confirm.remove.label"),
      {title: loc("yes"), fn: _.partial(triggerService.removeAssignmentTrigger, trigger.id)},
      {title: loc("no")}
    );
  };
};
