LUPAPISTE.OrganizationModel = function () {
  "use strict";

  var self = this;
  var authorizationModel = lupapisteApp.models.globalAuthModel;

  self.initialized = false;

  function EditLinkModel() {
    var self = this;

    self.links = ko.observableArray();
    self.commandName = ko.observable();
    self.command = null;

    self.init = function(params) {
      self.commandName(params.commandName);
      self.command = params.command;
      self.links.removeAll();
      self.links(_.map(params.langs, function(lang) {
        return {lang: lang,
                name: ko.observable(util.getIn(params, ["source", "name", lang], "")),
                url:  ko.observable(util.getIn(params, ["source", "url",  lang], ""))};
      }));
    };

    self.execute = function() {
      self.command(self.links());
    };

    self.ok = ko.computed(function() {
      return _.every(self.links(), function (l) {
        return !_.isBlank(l.name()) && !_.isBlank(l.url());
      });
    });
  }
  self.editLinkModel = new EditLinkModel();

  self.organizationId = ko.observable();
  self.langs = ko.observableArray();
  self.links = ko.observableArray();
  self.operationsAttachments = ko.observableArray();
  self.attachmentTypes = {};
  self.selectedOperations = ko.observableArray();
  self.allOperations = [];
  self.appRequiredFieldsFillingObligatory = ko.observable(false);
  self.automaticOkForAttachments = ko.observable(false);
  self.assignmentsEnabled = ko.observable(false);
  self.extendedConstructionWasteReportEnabled = ko.observable(false);
  self.validateVerdictGivenDate = ko.observable(true);
  self.automaticReviewFetchEnabled = ko.observable(true);
  self.onlyUseInspectionFromBackend = ko.observable(false);
  self.tosFunctions = ko.observableArray();
  self.tosFunctionVisible = ko.observable(false);
  self.archivingProjectTosFunction = ko.observable();
  self.permanentArchiveEnabled = ko.observable(true);
  self.permanentArchiveInUseSince = ko.observable();
  self.earliestArchivingDate = ko.observable();
  self.features = ko.observable();
  self.allowedRoles = ko.observable([]);
  self.permitTypes = ko.observable([]);
  self.useAttachmentLinksIntegration = ko.observable(false);
  self.inspectionSummariesEnabled = ko.observable(false);
  self.inspectionSummaryTemplates = ko.observableArray([]);
  self.operationsInspectionSummaryTemplates = ko.observable({});
  self.handlerRoles = ko.observableArray();
  self.assignmentTriggers = ko.observableArray();
  self.multipleOperationsSupported = ko.observable(false);
  self.removeHandlersFromRevertedDraft = ko.observable( false );

  self.sectionOperations = ko.observableArray();

  self.load = function() { ajax.query("organization-by-user").success(self.init).call(); };

  ko.computed(function() {
    var isObligatory = self.appRequiredFieldsFillingObligatory();
    if (self.initialized) {
      ajax.command("set-organization-app-required-fields-filling-obligatory", {enabled: isObligatory})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  ko.computed(function() {
    var automaticOk = self.automaticOkForAttachments();
    if (self.initialized) {
      ajax.command("set-automatic-ok-for-attachments", {enabled: automaticOk})
          .success(util.showSavedIndicator)
          .error(util.showSavedIndicator)
          .call();
    }
  });

  ko.computed(function() {
    var assignmentsEnabled = self.assignmentsEnabled();
    if (self.initialized) {
      ajax.command("set-organization-assignments", {enabled: assignmentsEnabled})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  ko.computed(function() {
    var inspectionSummariesEnabled = self.inspectionSummariesEnabled();
    if (self.initialized) {
      ajax.command("set-organization-inspection-summaries", {enabled: inspectionSummariesEnabled})
        .success(function(event) {
          util.showSavedIndicator(event);
          if (inspectionSummariesEnabled) {
            lupapisteApp.services.inspectionSummaryService.getTemplatesAsAuthorityAdmin();
          }
        })
        .error(util.showSavedIndicator)
        .call();
    }
  });

  ko.computed(function() {
    var extendedConstructionWasteReportEnabled = self.extendedConstructionWasteReportEnabled();
    if (self.initialized) {
      ajax.command("set-organization-extended-construction-waste-report", {enabled: extendedConstructionWasteReportEnabled})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  ko.computed(function() {
    var multipleOperationsSupported = self.multipleOperationsSupported();
    if (self.initialized) {
      ajax.command("set-organization-multiple-operations-support", {enabled: multipleOperationsSupported})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  ko.computed(function() {
    var removeHandlers = self.removeHandlersFromRevertedDraft();
    if (self.initialized) {
      ajax.command("set-organization-remove-handlers-from-reverted-draft",
                   {enabled: removeHandlers})
      .success(util.showSavedIndicator)
      .error(util.showSavedIndicator)
      .call();
    }
  });

  ko.computed(function() {
    var validateVerdictGivenDate = self.validateVerdictGivenDate();
    if (self.initialized) {
      ajax.command("set-organization-validate-verdict-given-date", {enabled: validateVerdictGivenDate})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  self.automaticReviewFetchEnabled.subscribe(function(automaticReviewFetchEnabled) {
    if (self.initialized) {
      ajax.command("set-organization-review-fetch-enabled", { enabled: automaticReviewFetchEnabled })
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  self.onlyUseInspectionFromBackend.subscribe(function(onlyUseInspectionFromBackend) {
    if (self.initialized) {
      ajax.command("set-only-use-inspection-from-backend", { enabled: onlyUseInspectionFromBackend })
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  ko.computed(function() {
    var useAttachmentLinks = self.useAttachmentLinksIntegration();
    if (self.initialized) {
      ajax.command("set-organization-use-attachment-links-integration", {enabled: useAttachmentLinks})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  self.validateVerdictGivenDateVisible = ko.pureComputed(function() {
    var types = self.permitTypes();
    return _.includes(types, "R") || _.includes(types, "P");
  });

  self.reviewFetchTogglerVisible = ko.pureComputed(function() {
    return _.includes(self.permitTypes(), "R");
  });

  function toAttachments(attachments) {
    return _(attachments || [])
      .map(function(a) { return {id: a, text: loc(["attachmentType", a[0], a[1]])}; })
      .sortBy("text")
      .value();
  }

  self.neighborOrderEmails = ko.observable("");
  ko.computed(function() {
    var emails = self.neighborOrderEmails();
    if (self.initialized) {
      ajax.command("set-organization-neighbor-order-email", {emails: emails})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  self.submitNotificationEmails = ko.observable("");
  ko.computed(function() {
    var emails = self.submitNotificationEmails();
    if (self.initialized) {
      ajax.command("set-organization-submit-notification-email", {emails: emails})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  self.infoRequestNotificationEmails = ko.observable("");
  ko.computed(function() {
    var emails = self.infoRequestNotificationEmails();
    if (self.initialized) {
      ajax.command("set-organization-inforequest-notification-email", {emails: emails})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  self.fundingNotificationEmails = ko.observable("");
  ko.computed(function() {
    var emails = self.fundingNotificationEmails();
    if (self.initialized) {
      ajax.command("set-organization-funding-enabled-notification-email", {emails: emails})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  ko.computed(function() {
    var startDate = self.permanentArchiveInUseSince();
    if (self.initialized && startDate) {
      ajax.command("set-organization-permanent-archive-start-date", {date: startDate.getTime()})
        .success(util.showSavedIndicator)
        .error(function(res) {
          util.showSavedIndicator(res);
          if (res.text === "error.invalid-date") {
            self.permanentArchiveInUseSince(null);
          }
        })
        .call();
    }
  });

  self.defaultDigitalizationLocationX = ko.observable("");
  self.defaultDigitalizationLocationY = ko.observable("");
  ko.computed(function() {
    var x = self.defaultDigitalizationLocationX();
    var y = self.defaultDigitalizationLocationY();
    if (self.initialized) {
      ajax.command("set-default-digitalization-location", {x: x, y: y})
        .success(util.showSavedIndicator)
        .error(util.showSavedIndicator)
        .call();
    }
  });

  function setTosFunctionForOperation(operationId, functionCode) {
    var cmd = functionCode !== null ? "set-tos-function-for-operation" : "remove-tos-function-from-operation";
    var data = {operation: operationId};
    if (functionCode !== null) {
      data.functionCode = functionCode;
    }
    ajax.command(cmd, data)
      .success(util.showSavedIndicator)
      .error(util.showSavedIndicator)
      .call();
  }

  ko.computed(function() {
    var tosFunction = self.archivingProjectTosFunction();
    if (self.initialized) {
      setTosFunctionForOperation("archiving-project", tosFunction);
    }
  });

  var sectionEnabled = ko.observable();

  self.verdictSectionEnabled = ko.computed( {
      read: function() {
        return sectionEnabled();
      },
      write: function( enabled ) {
        sectionEnabled( Boolean( enabled ));
        if( self.initialized ) {
          ajax.command( "section-toggle-enabled",
                        {flag: sectionEnabled()})
            .success( util.showSavedIndicator)
            .error( util.showSavedIndicator)
            .call();
        }
      }
    });

  hub.subscribe("inspectionSummaryService::templatesLoaded", function(event) {
    self.inspectionSummaryTemplates(event.templates);
    self.operationsInspectionSummaryTemplates(_.get(event, "operations-templates"));
  });


  // Pate verdict templates
  self.verdictTemplates = ko.observableArray( [] );
  self.defaultOperationVerdictTemplates = ko.observable( {} );

  function refreshVerdictTemplates() {
    ajax.query( "verdict-templates")
    .success( function( res ) {
      self.verdictTemplates( _.get( res, "verdict-templates", [] )  );
    })
    .call();
    ajax.query( "default-operation-verdict-templates")
    .success( function( res ) {
      self.defaultOperationVerdictTemplates( _.get( res, "templates", {} )  );
    })
    .call();
  }

  // Sent from pate/service.cljs
  hub.subscribe( "pate::verdict-templates-changed", refreshVerdictTemplates );

  self.init = function(data) {
    self.initialized = false;
    var organization = data.organization;
    self.organizationId(organization.id);
    ajax
      .query("all-operations-for-organization", {organizationId: organization.id})
      .success(function(data) {
        self.allOperations = data.operations;
      })
      .call();

    // Required fields in app obligatory to submit app
    //
    self.appRequiredFieldsFillingObligatory(organization["app-required-fields-filling-obligatory"] || false);

    self.assignmentsEnabled(organization["assignments-enabled"] || false);

    self.automaticOkForAttachments(organization["automatic-ok-for-attachments-enabled"] || false);

    self.extendedConstructionWasteReportEnabled(organization["extended-construction-waste-report-enabled"] || false);

    self.multipleOperationsSupported(organization["multiple-operations-supported"] || false);

    self.removeHandlersFromRevertedDraft( organization["remove-handlers-from-reverted-draft"] || false );

    self.validateVerdictGivenDate(organization["validate-verdict-given-date"] === true);

    self.automaticReviewFetchEnabled(organization["automatic-review-fetch-enabled"] === true);
    self.onlyUseInspectionFromBackend(organization["only-use-inspection-from-backend"] || false);

    self.permanentArchiveEnabled(organization["permanent-archive-enabled"] || false);
    self.permanentArchiveInUseSince(new Date(organization["permanent-archive-in-use-since"] || 0));
    var earliestArchivingTs = organization["earliest-allowed-archiving-date"];
    if (earliestArchivingTs > 0) {
      self.earliestArchivingDate(new Date(earliestArchivingTs));
    }

    self.inspectionSummariesEnabled(organization["inspection-summaries-enabled"] || false);

    if (organization["inspection-summaries-enabled"]) {
      lupapisteApp.services.inspectionSummaryService.getTemplatesAsAuthorityAdmin();
    }

    self.useAttachmentLinksIntegration(organization["use-attachment-links-integration"] === true);

    // Operation attachments
    //
    var operationsAttachmentsPerPermitType = organization.operationsAttachments || {};
    var localizedOperationsAttachmentsPerPermitType = [];

    self.langs(_.keys(organization.name));
    self.links(organization.links || []);

    var operationsTosFunctions = organization["operations-tos-functions"] || {};

    self.archivingProjectTosFunction(operationsTosFunctions["archiving-project"]);

    self.neighborOrderEmails(util.getIn(organization, ["notifications", "neighbor-order-emails"], []).join("; "));
    self.submitNotificationEmails(util.getIn(organization, ["notifications", "submit-notification-emails"], []).join("; "));
    self.infoRequestNotificationEmails(util.getIn(organization, ["notifications", "inforequest-notification-emails"], []).join("; "));

    self.defaultDigitalizationLocationX(util.getIn(organization, ["default-digitalization-location", "x"], []));
    self.defaultDigitalizationLocationY(util.getIn(organization, ["default-digitalization-location", "y"], []));

    _.forOwn(operationsAttachmentsPerPermitType, function(value, permitType) {
      var operationsAttachments = _(value)
        .map(function(v, k) {
          var attrs = {
            id: k,
            text: loc(["operations", k]),
            attachments: toAttachments(v),
            permitType: permitType,
            tosFunction: ko.observable(operationsTosFunctions[k])
          };
          attrs.tosFunction.subscribe(function(newFunctionCode) {
            setTosFunctionForOperation(k, newFunctionCode);
          });
          return attrs;
        })
        .sortBy("text")
        .value();
      localizedOperationsAttachmentsPerPermitType.push({permitType: permitType, operations: operationsAttachments});
    });

    self.operationsAttachments(localizedOperationsAttachmentsPerPermitType);
    self.attachmentTypes = data.attachmentTypes;

    // Selected operations
    //
    var selectedOperations = organization.selectedOperations || {};
    var localizedSelectedOperationsPerPermitType = [];

    _.forOwn(selectedOperations, function(value, permitType) {
      var selectedOperations = _(value)
        .map(function(v) {
          return {
            id: v,
            text: loc(["operations", v]),
            permitType: permitType
            };
          })
        .sortBy("text")
        .value();
      localizedSelectedOperationsPerPermitType.push({permitType: permitType, operations: selectedOperations});
    });

    self.selectedOperations(_.sortBy(localizedSelectedOperationsPerPermitType, "permitType"));

    // TODO test properly for timing issues
    if (authorizationModel.ok("available-tos-functions")) {
      ajax
        .query("available-tos-functions", {organizationId: organization.id})
        .success(function(data) {
          self.tosFunctions([{code: null, name: ""}].concat(data.functions));
          if (data.functions.length > 0 && organization["permanent-archive-enabled"]) {
            self.tosFunctionVisible(true);
          }
        })
        .call();
    }

    self.features(util.getIn(organization, ["areas"]));

    self.allowedRoles(organization.allowedRoles);

    self.permitTypes(_(organization.scope).map("permitType").uniq().value());

    // Section requirement for verdicts.
    sectionEnabled( _.get( organization, "section.enabled"));

    self.sectionOperations(_.get( organization, "section.operations", []));

    self.handlerRoles( _.get( organization, "handler-roles", []));

    self.assignmentTriggers( _.get( organization, "assignment-triggers", []));

    if( authorizationModel.ok("pate-enabled")) {
      refreshVerdictTemplates();
    }
    self.initialized = true;
  };

  self.isSectionOperation = function ( $data )  {
    return self.sectionOperations.indexOf( $data.id ) >= 0;
  };

  self.toggleSectionOperation = function( $data ) {
    var flag = !self.isSectionOperation( $data );
    if( flag ) {
      self.sectionOperations.push( $data.id );
    } else {
      self.sectionOperations.remove( $data.id );
    }
    ajax.command( "section-toggle-operation", {operationId: $data.id,
                                               flag: flag })
      .call();
  };

  function linksForCommand(links) {
    return _.reduce(links, function (acc, link) {
      acc.url[link.lang] = link.url();
      acc.name[link.lang] = link.name();
      return acc;
    }, {url: {}, name: {}});
  }

  self.editLink = function(indexFn) {
    var index = indexFn();
    self.editLinkModel.init({
      source: this,
      langs: self.langs(),
      commandName: "edit",
      command: function(links) {
        ajax
          .command("update-organization-link", _.merge({index: index},
                                                       linksForCommand(links)))
          .success(function() {
            self.load();
            LUPAPISTE.ModalDialog.close();
          })
          .call();
      }
    });
    self.openLinkDialog();
  };

  self.addLink = function() {
    self.editLinkModel.init({
      commandName: "add",
      langs: self.langs(),
      command: function(links) {
        ajax
          .command("add-organization-link", linksForCommand(links))
          .success(function() {
            self.load();
            LUPAPISTE.ModalDialog.close();
          })
          .call();
      }
    });
    self.openLinkDialog();
  };

  self.rmLink = function() {
    ajax
      .command("remove-organization-link", {url: this.url, name: this.name})
      .success(self.load)
      .call();
  };

  self.openLinkDialog = function() {
    LUPAPISTE.ModalDialog.open("#dialog-edit-link");
  };
};
