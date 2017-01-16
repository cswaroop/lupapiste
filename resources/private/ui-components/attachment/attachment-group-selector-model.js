LUPAPISTE.AttachmentGroupSelectorModel = function(params) {
  "use strict";
  var self = this;
  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel());

  var editable = params.editable || ko.observable(true);
  var authModel = params.authModel || lupapisteApp.models.applicationAuthModel;
  var currentGroup = params.currentGroup;

  self.componentTemplate = params.legacy ? "attachment-group-selector-legacy-template" : "attachment-group-selector-modern-template";

  var service = lupapisteApp.services.attachmentsService;

  function groupToString(group) {
    return _.filter([_.get(group, "id") ? "operation" : _.get(group, "groupType"), _.get(group, "id")], _.isString).join("-");
  }
  var groupMapping = {};

  self.selectableGroups = self.disposedComputed(function() {
    // Use group strings in group selector
    groupMapping = _.keyBy(service.groupTypes(), groupToString);
    return _.keys(groupMapping);
  });

  self.selectedGroup = self.disposedPureComputed({
    read: function() {
      return groupToString(currentGroup());
    },
    write: function(groupString) {
      currentGroup(_.get(groupMapping, groupString));
    }
  });

  self.getGroupOptionsText = function(itemStr) {
    var item = _.get(groupMapping, itemStr);
    if (_.get(item, "groupType") === "operation") {
      return _.filter([loc([item.name, "_group_label"]), item.description]).join(" - ");
    } else if (_.get(item, "groupType")) {
      return loc([item.groupType, "_group_label"]);
    }
  };

  self.updateAllowed = function() { return authModel.ok("set-attachment-meta") && editable(); };

};
