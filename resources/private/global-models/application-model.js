LUPAPISTE.EmptyApplicationModel = function() {
  "use strict";
  return {startedBy: {firstName: "", lastName: ""},
          closedBy: {firstName: "", lastName: ""},
          warrantyStart: null,
          warrantyEnd: null};
};

LUPAPISTE.ApplicationModel = function() {
  "use strict";
  var self = this;

  // POJSO
  self._js = {};

  function fullNameInit() {
    return {firstName: ko.observable( "" ),
           lastName: ko.observable( "")};
  }

  // Observables
  self.id = ko.observable();

  self.auth = ko.observable();
  self.infoRequest = ko.observable();
  self.openInfoRequest = ko.observable();
  self.state = ko.observable();
  self.stateChanged = ko.observable(false);
  self.submitted = ko.observable();
  self.location = ko.observable();
  self.municipality = ko.observable();
  self.permitType = ko.observable("R");
  self.propertyId = ko.observable();
  self.propertyIdSource = ko.observable();
  self.title = ko.observable();
  self.created = ko.observable();
  self.modified = ko.observable();
  self.started = ko.observable();
  self.startedBy = fullNameInit();
  self.closed = ko.observable();
  self.closedBy = fullNameInit();
  self.attachments = ko.observable([]);
  self.warrantyStart = ko.observable();
  self.showWarrantyStart = ko.observable();
  self.warrantyEnd = ko.observable();
  self.showWarrantyEnd = ko.observable();
  self.address = ko.observable();
  self.secondaryOperations = ko.observable();
  self.primaryOperation = ko.observable();
  self.allOperations = ko.observable();

  self.permitSubtype = ko.observable();
  self.permitSubtypeHelp = ko.pureComputed(function() {
    var opName = util.getIn(self, ["primaryOperation", "name"]);
    if (loc.hasTerm(["help", opName ,"subtype"])) {
      return "help." + opName + ".subtype";
    }
    return undefined;
  });
  self.permitSubtypes = ko.observableArray([]);
  self.permitSubtypeMandatory = ko.pureComputed(function() {
    return !self.permitSubtype() && !_.isEmpty(self.permitSubtypes());
  });

  self.operationsCount = ko.observable();
  self.applicant = ko.observable();
  self.assignee = ko.observable();
  self.authority = ko.observable({});
  self.neighbors = ko.observable([]);
  self.statements = ko.observable([]);
  self.tasks = ko.observable([]);
  self.tosFunction = ko.observable(null);
  self.metadata = ko.observable();
  self.processMetadata = ko.observable();
  self.kuntalupatunnukset = ko.observable();

  // Options
  self.optionMunicipalityHearsNeighbors = ko.observable(false);
  self.optionMunicipalityHearsNeighborsDisabled = ko.pureComputed(function() {
    return !lupapisteApp.models.applicationAuthModel.ok("set-municipality-hears-neighbors");
  });
  self.municipalityHearsNeighborsVisible = ko.pureComputed( function() {
    return lupapisteApp.models.applicationAuthModel.ok( "municipality-hears-neighbors-visible");
  });

  // Application indicator metadata fields
  self.unseenStatements = ko.observable();
  self.unseenVerdicts = ko.observable();
  self.unseenComments = ko.observable();
  self.unseenAuthorityNotice = ko.observable();
  self.attachmentsRequiringAction = ko.observable();
  self.fullyArchived = ko.observable();

  // Application metadata fields
  self.inPostVerdictState = ko.observable(false);
  self.stateSeq = ko.observable([]);
  self.currentStateInSeq = ko.pureComputed(function() {return _.includes(self.stateSeq(), self.state());});
  self.inPostSubmittedState = ko.observable(false); // TODO: remove
  self.vendorBackendId = ko.observable(); // TODO: remove
  self.applicantPhone = ko.observable();
  self.organizationMeta = ko.observable();
  self.neighbors = ko.observable([]);
  self.submitErrors = ko.observableArray();
  self.applicantCompanies = ko.observableArray();

  self.organization = ko.observable([]);

  self.organizationName = ko.pureComputed(function() {
    return self.organizationMeta() ? self.organizationMeta().name() : "";
  });
  self.requiredFieldsFillingObligatory = ko.pureComputed(function() {
    return self.organizationMeta() ? self.organizationMeta().requiredFieldsFillingObligatory() : false;
  });
  self.incorrectlyFilledRequiredFields = ko.observable([]);
  self.hasIncorrectlyFilledRequiredFields = ko.pureComputed(function() {
    return self.incorrectlyFilledRequiredFields() && self.incorrectlyFilledRequiredFields().length > 0;
  });
  self.fieldWarnings = ko.observable([]);
  self.hasFieldWarnings = ko.computed(function() {
    return self.fieldWarnings() && self.fieldWarnings().length > 0;
  });

  self.urgency = ko.observable();
  self.authorityNotice = ko.observable();
  self.tags = ko.observable();
  self.comments = ko.observable([]);

  self.summaryAvailable = ko.pureComputed(function() {
    return lupapisteApp.models.applicationAuthModel.ok("application-summary-tab-visible");
  });

  self.isArchivingProject = ko.pureComputed(function() {
    return self.permitType() === "ARK";
  });

  self.openTask = function( taskId ) {
    hub.send( "scrollService::push");
    taskPageController.setApplicationModelAndTaskId(self._js, taskId);
    pageutil.openPage("task",  self.id() + "/" + taskId);
  };

  self.taskGroups = ko.pureComputed(function() {
    var tasks = ko.toJS(self.tasks) || [];
    // TODO query without foreman tasks
    tasks = _.filter(tasks, function(task) {
      return !_.includes( ["task-vaadittu-tyonjohtaja", "task-katselmus", "task-katselmus-backend"],
                          task["schema-info"].name);
    });
    var schemaInfos = _.reduce(tasks, function(m, task){
      var info = task.schema.info;
      m[info.name] = info;
      return m;
    },{});

    var groups = _.groupBy(tasks, function(task) {return task.schema.info.name;});
    return _(groups)
      .keys()
      .map(function(n) {
        return {
          type: n,
          name: loc([n, "_group_label"]),
          order: schemaInfos[n].order,
          tasks: _.map(groups[n], function(task) {
            task.displayName = taskUtil.shortDisplayName(task);
            task.openTask = _.partial( self.openTask, task.id);
            task.statusName = LUPAPISTE.statuses[task.state] || "unknown";

            return task;
          })};})
      .sortBy("order")
      .valueOf();
  });

  self.primaryOperationName = ko.pureComputed(function() {
    var opName = util.getIn(self.primaryOperation, ["name"]);
    return !_.isEmpty(opName) ? "operations." + opName : "";
  });

  hub.subscribe("op-description-changed", function(e) {
    var opid = e["op-id"];
    var desc = e["op-desc"];

    if (e.appId === self.id()) {
      var operations = _.map(self.allOperations(), function(op) {
        return op.id() === opid ? op.description(desc) : op;
      });
      self.allOperations(operations);
    }
  });

  self.foremanTasks = ko.observable();

  self.nonpartyDocumentIndicator = ko.observable(0);
  self.partyDocumentIndicator = ko.observable(0);

  self.calendarNotificationIndicator = ko.observable(0);
  self.calendarNotificationsPending = ko.observableArray([]);

  self.bulletinOpDescription = ko.observable().extend({rateLimit: {timeout: 500, method: "notifyWhenChangesStop"}});
  self.opDescriptionIndicator = ko.observable().extend({notify: "always"});

  self.linkPermitData = ko.observable(null);
  self.appsLinkingToUs = ko.observable(null);
  self.pending = ko.observable(false);
  self.processing = ko.observable(false);
  self.invites = ko.observableArray([]);
//  self.showApplicationInfoHelp = ko.observable(false);
//  self.showPartiesInfoHelp = ko.observable(false);
  // self.showStatementsInfoHelp = ko.observable(false);
  // self.showNeighborsInfoHelp = ko.observable(false);
  // self.showVerdictInfoHelp = ko.observable(false);
  self.showSummaryInfoHelp = ko.observable(false);
  self.showConstructionInfoHelp = ko.observable(false);

  self.targetTab = ko.observable({tab: undefined, id: undefined});

  self.allowedAttachmentTypes = ko.observableArray([]);

  self.toBackingSystem = function() {
    window.open("/api/raw/redirect-to-vendor-backend?id=" + self.id(), "backend");
  };

  self.updateInvites = function() {
    invites.getInvites(function(data) {
      self.invites(_.filter(data.invites, function(invite) {
        return invite.application.id === self.id();
      }));
      if (self.hasPersonalInvites()) {
        self.showAcceptPersonalInvitationDialog();
      }
      if (self.hasCompanyInvites()) {
        self.showAcceptCompanyInvitationDialog();
      }
    });
  };

  // update invites when id changes
  self.id.subscribe(self.updateInvites);

  self.hasInvites = ko.computed(function() {
    return !_.isEmpty(self.invites());
  });

  self.hasPersonalInvites = ko.computed(function() {
    var id = util.getIn(lupapisteApp.models.currentUser, ["id"]);
    return !_.isEmpty(_.filter(self.invites(), ["user.id", id]));
  });

  self.hasCompanyInvites = ko.computed(function() {
    var id = util.getIn(lupapisteApp.models.currentUser, ["company", "id"]);
    return !_.isEmpty(_.filter(self.invites(), ["user.id", id]));
  });

  self.approveInvite = function(type) {
    ajax
      .command("approve-invite", {id: self.id(), "invite-type": type})
      .success(function() {
        self.reload();
        self.updateInvites();
      })
      .call();
    return false;
  };

  var acceptDecline = function(applicationId) {
    return function() {
      ajax
      .command("decline-invitation", {id: applicationId})
      .success(function() {pageutil.openPage("applications");})
      .call();
      return false;
    };
  };

  self.declineInvite = function() {
    LUPAPISTE.ModalDialog.showDynamicYesNo(
      loc("applications.declineInvitation.header"),
      loc("applications.declineInvitation.message"),
      {title: loc("yes"), fn: acceptDecline(self.id())},
      {title: loc("no")}
    );
  };

  // Required attachments

  self.missingRequiredAttachments = ko.pureComputed( function() {
    return _.get( lupapisteApp, "services.attachmentsService.missingRequiredAttachments", _.noop)();
  });

  self.hasMissingRequiredAttachments = ko.pureComputed(function() {
    return !_.isEmpty( self.missingRequiredAttachments());
  });

  self.missingSomeInfo = ko.pureComputed(function() {
    return self.hasFieldWarnings() || self.hasIncorrectlyFilledRequiredFields() || self.hasMissingRequiredAttachments();
  });

  self.submitButtonEnabled = ko.pureComputed(function() {
    return !self.stateChanged() && !self.processing() && !self.hasInvites() && (!self.requiredFieldsFillingObligatory() || !self.missingSomeInfo()) && _.isEmpty(self.submitErrors());
  });

  self.submitButtonFunction = ko.pureComputed(function() {
    if (lupapisteApp.models.applicationAuthModel.ok("submit-application")) {
      return self.submitApplication;
    } else if (lupapisteApp.models.applicationAuthModel.ok("submit-archiving-project")) {
      return self.submitArchivingProject;
    } else {
      return false;
    }
  });

  self.submitButtonKey = ko.pureComputed(function() {
    if (self.isArchivingProject()) {
      return lupapisteApp.models.currentUser.isArchivist() ? "digitizer.archiveProject" : "digitizer.submitProject";
    } else {
      return "application.submitApplication";
    }
  });

  self.reload = function() {
    self.submitErrors([]);
    repository.load(self.id());
  };

  self.reloadToTab = function(tabName) {
    self.submitErrors([]);
    repository.load(self.id(), undefined, _.partial(self.open, tabName));
  };

  self.lightReload = function() {
    repository.load(self.id(), undefined, undefined, true);
  };

  self.roles = ko.computed(function() {
    var withRoles = function(r, i) {
      if (i.id() === "" && i.invite) {
        i.id(util.getIn(i, ["invite", "user", "id"]));
      }
      var auth = r[i.id()] || (i.roles = [], i);
      var role = i.role();
      if (!_.includes(auth.roles, role)) {
        auth.roles.push(role);
      }
      r[i.id()] = auth;
      return r;
    };
    var pimped = _.reduce(self.auth(), withRoles, {});
    return _.values(pimped);
  });

  self.openOskariMap = function() {
    var featureParams = ["addPoint", "addArea", "addLine", "addCircle", "addEllipse"];
    var featuresEnabled = lupapisteApp.models.applicationAuthModel.ok("save-application-drawings") ? 1 : 0;
    var features = _.map(featureParams, function (f) {return f + "=" + featuresEnabled;}).join("&");
    var params = ["build=" + LUPAPISTE.config.build,
                  "id=" + self.id(),
                  "coord=" + self.location().x() + "_" + self.location().y(),
                  "zoomLevel=12",
                  "lang=" + loc.getCurrentLanguage(),
                  "municipality=" + self.municipality(),
                  features];

    var url = "/oskari/fullmap.html?" + params.join("&");
    window.open(url);
    hub.send("track-click", {category:"Application", label:"map", event:"openOskariMap"});
  };

  self.submitApplication = function() {
    if (!self.stateChanged()) {
      hub.send("track-click", {category:"Application", label:"submit", event:"submitApplication"});
      LUPAPISTE.ModalDialog.showDynamicYesNo(
          loc("application.submit.areyousure.title"),
          loc("application.submit.areyousure.message"),
          {title: loc("yes"),
            fn: function() {
              ajax.command("submit-application", {id: self.id()})
              .success( self.reload)
              .onError("error.cannot-submit-application", cannotSubmitResponse)
              .onError("error.command-illegal-state", self.lightReload)
              .fuse(self.stateChanged)
              .processing(self.processing)
              .call();
              hub.send("track-click", {category:"Application", label:"submit", event:"applicationSubmitted"});
              return false;
            }},
            {title: loc("no")}
      );
      hub.send("track-click", {category:"Application", label:"cancel", event:"applicationSubmitCanceled"});
    }
    return false;
  };

  self.submitArchivingProject = function() {
    if (!self.stateChanged()) {
      hub.send("track-click", {category:"Application", label:"submit", event:"submitApplication"});
      ajax.command("submit-archiving-project", {id: self.id()})
        .success(function(){
          if (lupapisteApp.models.currentUser.isArchivist()) {
            self.reloadToTab("archival");
          } else {
            self.reload();
          }
        })
        .onError("error.cannot-submit-application", cannotSubmitResponse)
        .onError("error.command-illegal-state", self.lightReload)
        .fuse(self.stateChanged)
        .processing(self.processing)
        .call();
      hub.send("track-click", {category:"Application", label:"submit", event:"applicationSubmitted"});
    }
    return false;
  };

  self.requestForComplement = function() {
    ajax.command("request-for-complement", { id: self.id()})
      .success(function() {
        ajax.command( "cleanup-krysp", {id: self.id()})
          .onError(_.noop)
          .call();
        self.reload();
      })
      .onError("error.command-illegal-state", self.lightReload)
      .fuse(self.stateChanged)
      .processing(self.processing)
      .call();
    return false;
  };

  self.convertToApplication = function() {
    ajax.command("convert-to-application", {id: self.id()})
      .success(function() {
        pageutil.openPage("application", self.id());
      })
      .fuse(self.stateChanged)
      .processing(self.processing)
      .call();
      hub.send("track-click", {category:"Inforequest", label:"", event:"convertToApplication"});
    return false;
  };

  self.nonApprovedDesigners = ko.observableArray([]);

  function checkForNonApprovedDesigners() {
    var nonApproved = _(docgen.nonApprovedDocuments()).filter(function(docModel) {
        return docModel.schema.info.subtype === "suunnittelija" && !docModel.docDisabled;
      })
      .map(function(docModel) {
        var title = loc([docModel.schemaName, "_group_label"]);
        var accordionService = lupapisteApp.services.accordionService;
        var identifierField = accordionService.getIdentifier(docModel.docId);
        var identifier = identifierField && identifierField.value();
        var operation = null; // We'll assume designer is never attached to operation
        var docData = accordionService.getDocumentData(docModel.docId); // The current data
        var accordionText = docutils.accordionText(docData.accordionPaths, docData.data);
        var headerDescription = docutils.headerDescription(identifier, operation, accordionText);

        return title + headerDescription;
      })
      .value();
    self.nonApprovedDesigners(nonApproved);
  }

  hub.subscribe("update-doc-success", checkForNonApprovedDesigners);
  hub.subscribe("application-model-updated", checkForNonApprovedDesigners);

  self.approveApplication = function() {
    if (self.stateChanged()) {
      return false;
    }

    var approve = function() {
      ajax.command("approve-application", {id: self.id(), lang: loc.getCurrentLanguage()})
        .success(function(resp) {
          self.reloadToTab("info");
          if (!resp.integrationAvailable) {
            hub.send("show-dialog", {ltitle: "integration.title",
                                     size: "medium",
                                     component: "ok-dialog",
                                     componentParams: {ltext: "integration.unavailable"}});
          } else if (self.externalApi.enabled()) {
            var permit = externalApiTools.toExternalPermit(self._js);
            hub.send("external-api::integration-sent", permit);
          }
        })
        .onError("error.command-illegal-state", self.lightReload)
        .error(function(e) {LUPAPISTE.showIntegrationError("integration.title", e.text, e.details);})
        .fuse(self.stateChanged)
        .processing(self.processing)
        .call();
      hub.send("track-click", {category:"Application", label:"", event:"approveApplication"});
    };

    if (!( lupapisteApp.models.applicationAuthModel.ok( "statements-after-approve-allowed")
           || _(self._js.statements).reject("given").isEmpty())) {
      // All statements have not been given
      hub.send("show-dialog", {ltitle: "application.approve.statement-not-requested",
        size: "medium",
        component: "yes-no-dialog",
        componentParams: {ltext: "application.approve.statement-not-requested-warning-text",
          yesFn: approve}});
    } else {
      approve();
    }
    return false;
  };

  self.partiesAsKrysp = function() {
    var sendParties = function() {
      ajax.command("parties-as-krysp", {id: self.id(), lang: loc.getCurrentLanguage()})
        .success(function(resp) {
          hub.send("indicator", {style: "positive", rawMessage: loc("integration.parties.sent", resp.sentDocuments.length), sticky: true});
          self.lightReload();
        })
        .onError("error.command-illegal-state", self.lightReload)
        .error(function(e) {LUPAPISTE.showIntegrationError("integration.title", e.text, e.details);})
        .processing(self.processing)
        .call();
    };

    // All designers have not been approved?
    if (!_.isEmpty(self.nonApprovedDesigners())) {
      var text = loc("application.designers-not-approved-help") + "<ul><li>" + self.nonApprovedDesigners().join("</li><li>") + "</li></ul>";
      hub.send("show-dialog", {ltitle: "application.designers-not-approved",
      size: "medium",
      component: "yes-no-dialog",
      componentParams: {text: text, yesFn: sendParties, lyesTitle: "continue", lnoTitle: "cancel"}});
    } else {
      sendParties();
    }
  };

  self.approveExtension = function() {
    LUPAPISTE.ModalDialog.showDynamicYesNo(
      loc("application.extension.approve"),
      loc("application.extension.approve-confirmation"),
      {title: loc("ok"), fn: self.approveApplication},
      {title: loc("cancel")}
    );
  };

  self.refreshKTJ = function() {
    ajax.command("refresh-ktj", {id: self.id()})
      .success(function() {
        self.reload();
        LUPAPISTE.ModalDialog.showDynamicOk(loc("integration.title"), loc("application.refreshed"));
      })
      .processing(self.processing)
      .call();
    hub.send("track-click", {category:"Application", label:"", event:"refreshKTJ"});
    return false;
  };

  self.findOwners = function() {
    hub.send("show-dialog", { ltitle: "neighbor.owners.title",
      size: "large",
      component: "neighbors-owners-dialog",
      componentParams: {applicationId: self.id()} });
    hub.send("track-click", {category:"Application", label:"", event:"findOwners"});
    return false;
  };

  self.isNotOwner = function(model) {
    return model.role() !== "owner";
  };

  self.userHasRole = function(userModel, role) {
    return _(util.getIn(self.roles()))
      .filter(function(r) { return r.id() === util.getIn(userModel, ["id"]); })
      .invokeMap("role")
      .includes(role);
  };

  self.canSubscribe = function(model) {
    return model.role() !== "statementGiver" &&
           lupapisteApp.models.currentUser &&
           (lupapisteApp.models.currentUser.isAuthority() || lupapisteApp.models.currentUser.id() ===  model.id()) &&
           lupapisteApp.models.applicationAuthModel.ok("subscribe-notifications") &&
           lupapisteApp.models.applicationAuthModel.ok("unsubscribe-notifications");
  };

  self.manageSubscription = function(command, model) {
    if (self.canSubscribe(model)) {
      ajax.command(command, {id: self.id(), username: model.username()})
        .success(self.reload)
        .processing(self.processing)
        .pending(self.pending)
        .call();
    }
  };

  self.subscribeNotifications = _.partial(self.manageSubscription, "subscribe-notifications");
  self.unsubscribeNotifications = _.partial(self.manageSubscription, "unsubscribe-notifications");

  self.addOperation = function() {
    pageutil.openPage("add-operation", self.id());
    hub.send("track-click", {category:"Application", label:"", event:"addOperation"});
    return false;
  };

  self.cancelInforequest = function() {
    if (!self.stateChanged()) {
      hub.send("track-click", {category:"Inforequest", label:"", event:"cancelInforequest"});
      LUPAPISTE.ModalDialog.showDynamicYesNo(
        loc("areyousure"),
        loc("areyousure.cancel-inforequest"),
        {title: loc("yes"),
         fn: function() {
          ajax
            .command("cancel-inforequest", {id: self.id()})
            .success(function() {pageutil.openPage("applications");})
            .onError("error.command-illegal-state", self.lightReload)
            .fuse(self.stateChanged)
            .processing(self.processing)
            .call();
          hub.send("track-click", {category:"Inforequest", label:"", event:"infoRequestCanceled"});
          return false;}},
        {title: loc("no")}
      );
      hub.send("track-click", {category:"Inforequest", label:"", event:"infoRequestCancelCanceled"});
    }
    return false;
  };

  self.cancelText = ko.observable("");

  function cancelApplicationAjax(command) {
    return function() {
      ajax
        .command(command, {id: self.id(), text: self.cancelText(), lang: loc.getCurrentLanguage()})
        .success(function() {
          self.cancelText("");
          if (command === "cancel-application") {
            // regular user, can't undo cancellation so redirect to applications view
            pageutil.openPage("applications");
          } else { // authority, can undo so don't redirect, just reload application to canceled state
            self.lightReload();
          }
        })
        .onError("error.command-illegal-state", self.lightReload)
        .fuse(self.stateChanged)
        .processing(self.processing)
        .call();
      return false;
    };
  }

  self.cancelApplication = function() {
    if (!self.stateChanged()) {
      var command = lupapisteApp.models.applicationAuthModel.ok( "cancel-application-authority")
            ? "cancel-application-authority"
            : "cancel-application";
      hub.send("track-click", {category:"Application", label:"", event:"cancelApplication"});
      hub.send("show-dialog", {ltitle: self.isArchivingProject() ? "application.cancelArchivingProject" : "application.cancelApplication",
                               size: "medium",
                               component: self.isArchivingProject() ? "yes-no-dialog" : "textarea-dialog",
                               componentParams: {text: self.isArchivingProject() ? loc("areyousure.cancelArchivingProject") : loc("areyousure.cancel-application"),
                                                 yesFn: cancelApplicationAjax(command),
                                                 lyesTitle: "yes",
                                                 lnoTitle: "no",
                                                 textarea: {llabel: "application.canceled.reason",
                                                            rows: 10,
                                                            observable: self.cancelText}}});
    }
  };

  self.undoCancellation = function() {
    if (!self.stateChanged()) {
      var sendCommand = ajax
                          .command("undo-cancellation", {id: self.id()})
                          .success(function() {
                            repository.load(self.id());
                          })
                          .onError("error.command-illegal-state", self.lightReload)
                          .fuse(self.stateChanged)
                          .processing(self.processing);

      hub.send("show-dialog", {ltitle: "application.undoCancellation",
                               size: "medium",
                               component: "yes-no-dialog",
                               componentParams: {text: loc("application.undoCancellation.areyousure", loc(util.getPreviousState(self._js))),
                                                 yesFn: function() { sendCommand.call(); }}});
    }
  };

  self.exportPdf = function() {
    window.open("/api/raw/pdf-export?id=" + self.id() + "&lang=" + loc.currentLanguage, "_blank");
    return false;
  };


  self.newOtherAttachment = function() {
    // TODO: refactor this function away from inforequest.html
    attachment.initFileUpload({
      applicationId: self.id(),
      attachmentId: null,
      attachmentType: "muut.muu",
      typeSelector: false
    });
    LUPAPISTE.ModalDialog.open("#upload-dialog");
    hub.send("track-click", {category:"Application", label:"", event:"newOtherAttachment"});
  };

  self.createChangePermit = function() {
    hub.send("track-click", {category:"Application", label:"", event:"createChangePermit"});
    LUPAPISTE.ModalDialog.showDynamicYesNo(
      loc("application.createChangePermit.areyousure.title"),
      loc("application.createChangePermit.areyousure.message"),
      {title: loc("application.createChangePermit.areyousure.ok"),
       fn: function() {
        ajax
          .command("create-change-permit", {id: self.id()})
          .success(function(data) {
            pageutil.openPage("application", data.id);
          })
          .processing(self.processing)
          .call();
        return false;
      }},
      {title: loc("cancel")}
    );
    return false;

  };


  self.doCreateContinuationPeriodPermit = function() {
    hub.send("track-click", {category:"Application", label:"", event:"doCreateContinuationPeriodPermit"});
    ajax
      .command("create-continuation-period-permit", {id: self.id()})
      .success(function(data) {
        pageutil.openPage("application", data.id);
      })
      .processing(self.processing)
      .call();
  };

  self.createContinuationPeriodPermit = function() {
    LUPAPISTE.ModalDialog.showDynamicYesNo(
        loc("application.createContinuationPeriodPermit.confirmation.title"),
        loc("application.createContinuationPeriodPermit.confirmation.message"),
        {title: loc("yes"), fn: self.doCreateContinuationPeriodPermit},
        {title: loc("no")}
    );
  };

  self.resetIndicators = function() {
    hub.send("track-click", {category:"Application", label:"", event:"resetIndicators"});
    ajax
      .command("mark-everything-seen", {id: self.id()})
      .success(self.reload)
      .processing(self.processing)
      .call();
  };

  function focusOnElement(id, retryLimit) {
    var targetElem = document.getElementById(id);

    if (!retryLimit) {
      if (targetElem) {
        // last chance: hope that the browser scrolls to somewhere near the focused element.
        targetElem.focus();
      }
      // no more retries and no element: give up
      return;
    }

    var offset = $(targetElem).offset();

    if (!offset || offset.left === 0 || !targetElem) {
      // Element is not yet visible, wait for a short moment.
      // Because of the padding, offset left is never zero when
      // the element is visible.
      setTimeout(_.partial(focusOnElement, id, --retryLimit), 5);
    } else {
      var navHeight = $("nav").first().height() || 0;
      var roomForLabel = (targetElem.nodeName === "UL") ? 0 : 30;
      window.scrollTo(0, offset.top - navHeight - roomForLabel);
      targetElem.focus();
    }
  }

  self.open = function(tab) {
    var suffix = self.infoRequest() ? null : tab;
    pageutil.openApplicationPage(self, suffix);
  };

  self.targetTab.subscribe(function(target) {
    if (target.tab === "requiredFieldSummary") {
      ajax
        .query("fetch-validation-errors", {id: self.id.peek()})
        .success(function (data) {
          self.updateMissingApplicationInfo(data.results);
          checkForNonApprovedDesigners();
        })
        .processing(self.processing)
        .call();
    }
    self.open(target.tab);
    if (target.id) {
      var maxRetries = 10; // quite arbitrary, might need to increase for slower browsers
      focusOnElement(target.id, maxRetries);
    }
  });

  self.changeTab = function(model,event) {
    self.targetTab({tab: $(event.target).closest("a").attr("data-target"), id: null});
    hub.send("track-click", {category:"Application", label:self.targetTab().tab, event:"changeTab"});
  };

  self.nextTab = function(model,event) {
    self.targetTab({tab: $(event.target).closest("a").attr("data-target"), id: "applicationTabs"});
    hub.send("track-click", {category:"Application", label:self.targetTab().tab, event:"nextTab"});
  };

  // called from application actions
  self.goToApplicationApproval = function() {
    self.targetTab({tab:"requiredFieldSummary",id:"applicationTabs"});
  };

  self.moveToIncorrectlyFilledRequiredField = function(fieldInfo) {
    AccordionState.set( fieldInfo.document.id, true );
    var targetId = fieldInfo.document.id + "-" + fieldInfo.path.join("-");
    self.targetTab({tab: (fieldInfo.document.type !== "party") ? "info" : "parties", id: targetId});
  };

  self.updateMissingApplicationInfo = function(errors) {
    self.incorrectlyFilledRequiredFields(util.extractRequiredErrors(errors));
    self.fieldWarnings(util.extractWarnErrors(errors));
    fetchApplicationSubmittable();
  };

  function cannotSubmitResponse(data) {
    self.submitErrors(_.map(data.errors, "text"));
  }

  function fetchApplicationSubmittable() {
    if (lupapisteApp.models.applicationAuthModel.ok("submit-application")) {
      ajax
        .query("application-submittable", {id: self.id.peek()})
        .success(function() { self.submitErrors([]); })
        .onError("error.cannot-submit-application", cannotSubmitResponse)
        .onError("error.command-illegal-state", self.lightReload)
        .call();
    }
  }

  self.toggleHelp = function(param) {
    self[param](!self[param]());
  };

  self.toAsianhallinta = function() {
    ajax.command("application-to-asianhallinta", {id: self.id(), lang: loc.getCurrentLanguage()})
      .success(function() {
        self.reload();
      })
      .error(function(e) {LUPAPISTE.showIntegrationError("integration.asianhallinta.title", e.text, e.details);})
      .processing(self.processing)
      .call();
  };


  function returnToDraftAjax() {
    ajax.command("return-to-draft", {id: self.id(), lang: loc.getCurrentLanguage(), text: self.returnToDraftText()})
      .success(function() {
        self.returnToDraftText("");
        self.reload();
      })
      .error(function() { self.reload(); })
      .fuse(self.stateChanged)
      .processing(self.processing)
      .call();
    return false;
  }

  self.returnToDraftText = ko.observable("");
  self.returnToDraft = function() {
    if (!self.stateChanged()) {
      hub.send("track-click", {category:"Application", label:"", event:"returnApplicationToDraft"});
      hub.send("show-dialog", {ltitle: "application.returnToDraft.title",
                               size: "large",
                               component: "textarea-dialog",
                               componentParams: {text: loc("application.returnToDraft.areyousure"),
                                                 yesFn: returnToDraftAjax,
                                                 lyesTitle: "application.returnToDraft.areyousure.confirmation",
                                                 lnoTitle: "cancel",
                                                 textarea: {llabel: "application.returnToDraft.reason",
                                                            rows: 10,
                                                            observable: self.returnToDraftText}}});
    }
  };

  self.showAcceptPersonalInvitationDialog = function() {
    if (self.hasPersonalInvites() && lupapisteApp.models.applicationAuthModel.ok("approve-invite")) {
      hub.send("show-dialog", {ltitle: "application.inviteSend",
                               size: "medium",
                               component: "yes-no-dialog",
                               componentParams: {ltext: "application.inviteDialogText",
                                                 lyesTitle: "applications.approveInvite",
                                                 lnoTitle: "application.showApplication",
                                                 yesFn: self.approveInvite}});
    }
  };

  self.showAcceptCompanyInvitationDialog = function() {
    if (self.hasCompanyInvites() && lupapisteApp.models.applicationAuthModel.ok("approve-invite")) {
      hub.send("show-dialog", {ltitle: "application.inviteSend",
                               size: "medium",
                               component: "yes-no-dialog",
                               componentParams: {ltext: "application.inviteCompanyDialogText",
                                                 lyesTitle: "applications.approveInvite",
                                                 lnoTitle: "application.showApplication",
                                                 yesFn: _.partial(self.approveInvite, "company")}});
    }
  };

  self.showAddPropertyButton = ko.pureComputed( function () {
    var primaryOp = lupapisteApp.models.application.primaryOperation();

    return lupapisteApp.models.applicationAuthModel.ok("create-doc") &&
      _.includes(util.getIn(primaryOp, ["optional"]), "secondary-kiinteistot");
  });

  self.addProperty = function() {
    hub.send("show-dialog", {ltitle: "application.dialog.add-property.title",
                             size: "medium",
                             component: "add-property-dialog"});
  };

  self.externalApi = {
    enabled: ko.pureComputed(function() { return lupapisteApp.models.rootVMO.externalApi.enabled(); }),
    ok: function(fnName) { return lupapisteApp.models.rootVMO.externalApi.ok(fnName); },
    showOnMap: function(model) {
      var permit = externalApiTools.toExternalPermit(model._js);
      hub.send("external-api::show-on-map", permit);
    },
    openApplication: function(model) {
      var permit = externalApiTools.toExternalPermit(model._js);
      hub.send("external-api::open-application", permit);
    }};

  // Saved from the old LUPAPISTE.AttachmentsTabModel, used in info request
  self.deleteSingleAttachment = function(a) {
    var versions = util.getIn(a, ["versions"]);
    var doDelete = function() {
      lupapisteApp.services.attachmentsService.removeAttachment(util.getIn(a, ["id"]));
      hub.send("track-click", {category:"Attachments", label: "", event:"deleteAttachmentFromListing"});
      return false;
    };
    hub.send("show-dialog", {ltitle: "attachment.delete.header",
                             size: "medium",
                             component: "yes-no-dialog",
                             componentParams: {ltext: _.isEmpty(versions) ? "attachment.delete.message.no-versions" : "attachment.delete.message",
                                               yesFn: doDelete}});
  };

  self.verdictPostfix = function () {
    if (this.permitSubtype() === "sijoitussopimus") {
      return ".sijoitussopimus";
    } else {
      return "";
    }
  };

  self.copy = function() {
    pageutil.openPage("copy",  self.id());
  };

  self.createDiggingPermit = function() {
    pageutil.openPage("create-digging-permit",  self.id());
  };

  self.canBeCopied = ko.observable(false);
  hub.subscribe("application-model-updated", function() {
    ajax
      .query("application-copyable", {"source-application-id": self.id()})
      .success(function() {
        self.canBeCopied(true);
      })
      .error(function() {
        self.canBeCopied(false);
      })
      .call();
    return false;
  });

  self.requiredFieldSummaryButtonVisible = ko.pureComputed(function() {
    return _.includes(["draft", "open", "submitted", "complementNeeded"], ko.unwrap(self.state));
  });

  self.requiredFieldSummaryButtonKey = ko.pureComputed(function() {
    if (self.isArchivingProject()) {
      return "archivingProject.tabRequiredFieldSummary";
    } else if (lupapisteApp.models.applicationAuthModel.ok("approve-application") ||
               lupapisteApp.models.applicationAuthModel.ok("update-app-bulletin-op-description")) {
      return "application.tabRequiredFieldSummary.afterSubmitted";
    } else {
      return "application.tabRequiredFieldSummary";
    }
  });

  self.requiredFieldSummaryButtonClass = ko.pureComputed(function() {
    if (lupapisteApp.models.applicationAuthModel.ok("approve-application") ||
        lupapisteApp.models.applicationAuthModel.ok("update-app-bulletin-op-description") ||
        _.includes(["draft", "open"], ko.unwrap(self.state))) {
      return "link-btn-inverse";
    } else {
      return "link-btn";
    }
  });

  self.gotoLinkPermitCard = _.partial( hub.send,
                                       "cardService::select",
                                       {card: "add-link-permit",
                                        deck: "summary"});

  self.doRemoveLinkPermit = function(linkPermitId) {
    ajax.command("remove-link-permit-by-app-id", {id: self.id(), linkPermitId: linkPermitId})
      .processing(self.processing)
      .pending(self.pending)
      .success(function() {
        repository.load(self.id());
      })
      .call();
  };

  self.removeSelectedLinkPermit = function(linkPermit) {
    hub.send("show-dialog", {ltitle: "linkPermit.remove.header",
                             size: "medium",
                             component: "yes-no-dialog",
                             componentParams: {text: loc("linkPermit.remove.message", linkPermit.id()),
                                               yesFn: _.partial(self.doRemoveLinkPermit, linkPermit.id())}});
  };

};
