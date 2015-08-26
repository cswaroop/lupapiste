jQuery(document).ready(function() {
  "use strict";

  var components = [
    "modal-dialog",
    "message-panel",
    "checkbox",
    "fill-info",
    "foreman-history",
    "foreman-other-applications",
    "select-component",
    "string",
    "attachments-multiselect",
    "authority-select",
    "authority-select-dialog",
    "autocomplete",
    "export-attachments",
    "neighbors-owners-dialog",
    "neighbors-edit-dialog",
    "company-selector",
    "company-invite",
    "company-invite-dialog",
    "submit-button-group",
    "yes-no-dialog",
    "yes-no-button-group",
    "company-registration-init",
    "invoice-operator-selector",
    "ok-dialog",
    "ok-button-group",
    "company-edit",
    "tags-editor",
    "upload",
    "openlayers-map",
    "vetuma-init",
    "vetuma-status",
    "help-toggle",
    "applications-search",
    "applications-search-results",
    "applications-search-filter",
    "applications-search-paging",
    "autocomplete-grouping"
  ];

  _.forEach(components, function(component) {
    ko.components.register(component, {
      viewModel: LUPAPISTE[_.capitalize(_.camelCase(component)) + "Model"],
      template: { element: component + "-template"}
    });
  });
});
