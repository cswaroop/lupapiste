;(function() {
  "use strict";

  var tree;

  function CreateApplicationModel() {
    var self = this;

    self.locationModel = new LUPAPISTE.CreateApplicationLocationModel({mapId: "archiving-map", category: "Create", nextStep: "digitizing-location-selected"});

    hub.subscribe("digitizing-location-selected", function() {
      hub.send("track-click", {category:"Create", label:"map", event:"mapContinue"});
      self.createArchivingProject(true);
    });

    self.search = ko.observable("");
    self.searching = ko.observable(false);

    self.operations = ko.observable(null);
    self.organization = ko.observable(null);
    self.attachmentsForOp = ko.pureComputed(function() {
      var m = self.organization();
      return m ? _.map(m.attachmentsForOp, function(d) { return { group: d[0], id: d[1]};}) : null;
    });

    self.processing = ko.observable(false);
    self.pending = ko.observable(false);
    self.inforequestsDisabled = ko.observable(false);
    self.newApplicationsDisabled = ko.observable(false);
    self.operation = ko.observable();
    self.message = ko.observable("");

    // Observables for creating new application from previous permit
    self.creatingAppWithPrevPermit = false;
    self.organizationOptions = ko.observable([]);
    self.selectedPrevPermitOrganization = ko.observable(null);
    self.kuntalupatunnusFromPrevPermit = ko.observable(null);
    self.needMorePrevPermitInfo = ko.observable(false);
    self.authorizeApplicants = ko.observable(true);
    self.creatingAppWithPrevPermitOk = ko.pureComputed(function() {
      return !self.processing() && !self.pending() &&
             !_.isBlank(self.kuntalupatunnusFromPrevPermit()) &&
             !_.isBlank(self.selectedPrevPermitOrganization()) &&
             ( !self.needMorePrevPermitInfo() || (self.locationModel.propertyIdOk() &&
                                                  !_.isBlank(self.locationModel.address()) &&
                                                  !_.isBlank(self.search()) &&
                                                  self.locationModel.hasXY()));
      });

    self.permitNotFound = ko.observable(false);

    ko.computed(function() {
      var code = self.locationModel.municipalityCode();
      if (code && !self.creatingAppWithPrevPermit) {
        self.findOperations(code);
      }
    });

    self.findOperations = function(code) {
      municipalities.operationsForMunicipality(code, function(operations) {
        self.operations(operations);
      });
      return self;
    };

    self.resetLocation = function() {
      self.locationModel.reset();
      return self;
    };

    self.clear = function() {
      self.locationModel.clearMap();

      self.creatingAppWithPrevPermit = false;
      return self
        .resetLocation()
        .search("")
        .message("")
        .kuntalupatunnusFromPrevPermit(null)
        .organizationOptions([])
        .selectedPrevPermitOrganization(null)
        .needMorePrevPermitInfo(false)
        .permitNotFound(false);
    };

    self.createOK = ko.pureComputed(function() {
      return self.locationModel.propertyIdOk() && self.locationModel.addressOk() && !self.processing();
    });

    //
    // Callbacks:
    //

    // Search activation:

    self.searchNow = function() {
      hub.send("track-click", {category:"Create", label:"map", event:"searchLocation"});
      self.locationModel.clearMap().reset();
      self.locationModel.beginUpdateRequest()
        .searchPoint(self.search(), self.searching);
      return false;
    };

    var zoomLevelEnum = {
      "540": 9,
      "550": 7,
      "560": 9
    };

    // Return function that calls every function provided as arguments to 'comp'.
    function comp() {
      var fs = arguments;
      return function() {
        var args = arguments;
        _.each(fs, function(f) {
          f.apply(self, args);
        });
      };
    }

    function zoom(item, level) {
      var zoomLevel = level || zoomLevelEnum[item.type] || 11;
      self.locationModel.center(zoomLevel, item.location.x, item.location.y);
    }
    function zoomer(level) { return function(item) { zoom(item, level); }; }
    function fillMunicipality(item) {
      self.search(", " + loc(["municipality", item.municipality]));
      if (self.creatingAppWithPrevPermit) {
        $("#prev-permit-address-search").caretToStart();
      } else {
        $("#create-search").caretToStart();
      }
    }
    function fillAddress(item) {
      self.search(item.street + " " + item.number + ", " + loc(["municipality", item.municipality]));
      var addressEndIndex = item.street.length + item.number.toString().length + 1;
      if (self.creatingAppWithPrevPermit) {
        $("#prev-permit-address-search").caretTo(addressEndIndex);
      } else {
        $("#create-search").caretTo(addressEndIndex);
      }
    }

    function selector(item) { return function(value) { return _.every(value[0], function(v, k) { return item[k] === v; }); }; }
    function toHandler(value) { return value[1]; }
    function invoker(item) { return function(handler) { return handler(item); }; }

    var handlers = [
      [{kind: "poi"}, comp(zoom, fillMunicipality)],
      [{kind: "address"}, comp(fillAddress, self.searchNow)],
      [{kind: "address", type: "street"}, zoomer(13)],
      [{kind: "address", type: "street-city"}, zoomer(13)],
      [{kind: "address", type: "street-number"}, zoomer(14)],
      [{kind: "address", type: "street-number-city"}, zoomer(14)],
      [{kind: "property-id"}, comp(zoomer(14), self.searchNow)]
    ];

    var renderers = [
      [{kind: "poi"}, function(item) {
        return $("<a>")
          .addClass("create-find")
          .addClass("poi")
          .append($("<span>").addClass("name").text(item.text))
          .append($("<span>").addClass("municipality").text(loc(["municipality", item.municipality])))
          .append($("<span>").addClass("type").text(loc(["poi.type", item.type])));
      }],
      [{kind: "address"}, function(item) {
        var a = $("<a>")
          .addClass("create-find")
          .addClass("address")
          .append($("<span>").addClass("street").text(item.street));
        if (item.number) {
          a.append($("<span>").addClass("number").text(item.number));
        }
        if (item.municipality) {
          a.append($("<span>").addClass("municipality").text(loc(["municipality", item.municipality])));
        }
        return a;
      }],
      [{kind: "property-id"}, function(item) {
        return $("<a>")
          .addClass("create-find")
          .addClass("property-id")
          .append($("<span>").text(util.prop.toHumanFormat(item["property-id"])));
      }]
    ];

    self.autocompleteSelect = function(e, data) {
      var item = data.item;
      _(handlers).filter(selector(item)).map(toHandler).each(invoker(item));
      return false;
    };

    self.autocompleteRender = function(ul, data) {
      var element = _(renderers).filter(selector(data)).take(1).map(toHandler).map(invoker(data)).value();
      return $("<li>")
        .append(element)
        .appendTo(ul);
    };

    self.updateOrganizationDetails = function(operation) {
      if (self.locationModel.municipalityCode() && operation) {
        ajax
          .query("organization-details", {
            municipality: self.locationModel.municipalityCode(),
            operation: operation,
            lang: loc.getCurrentLanguage()
          })
          .success(function(d) {
            self.inforequestsDisabled(d["inforequests-disabled"]);
            self.newApplicationsDisabled(d["new-applications-disabled"]);
            self.organization(d);
          })
          .error(function() {
            // TODO display error message?
            self.inforequestsDisabled(true);
            self.newApplicationsDisabled(true);
          })
          .call();
      }
    };

    self.create = function(infoRequest) {
      if (infoRequest) {
        if (self.inforequestsDisabled()) {
          hub.send("track-click", {category:"Create", label:"tree", event:"infoRequestDisabled'"});
          LUPAPISTE.ModalDialog.showDynamicOk(
              loc("new-applications-or-inforequests-disabled.dialog.title"),
              loc("new-applications-or-inforequests-disabled.inforequests-disabled"));
          return;
        }
        hub.send("track-click", {category:"Create", label:"tree", event:"newInfoRequest'"});
        LUPAPISTE.ModalDialog.showDynamicOk(loc("create.prompt.title"), loc("create.prompt.text"));
      } else if (self.newApplicationsDisabled()) {
        LUPAPISTE.ModalDialog.showDynamicOk(
            loc("new-applications-or-inforequests-disabled.dialog.title"),
            loc("new-applications-or-inforequests-disabled.new-applications-disabled"));
        hub.send("track-click", {category:"Create", label:"tree", event:"newApplicationsDisabled"});
        return;
      }

      var op = self.operation();
      if (!op) {
        error("No operation!", {selected: tree.getSelected(), stack: tree.getStack()});
      }

      var params = self.locationModel.toJS();
      params.infoRequest = infoRequest;
      params.operation = op;
      params.messages =  _.isBlank(self.message()) ? [] : [self.message()];

      ajax.command("create-application", params)
        .processing(self.processing)
        .pending(self.pending)
        .success(function(data) {
          self.clear();
          params.id = data.id;
          pageutil.openApplicationPage(params);
        })
        .call();
      hub.send("track-click", {category:"Create", label:"tree", event:"newApplication"});
    };
    self.createApplication = self.create.bind(self, false);
    self.createInfoRequest = self.create.bind(self, true);

    //
    // For creating new application based on a previous permit
    //

    self.initCreateAppWithPrevPermit = function() {
      self.clear();
      self.creatingAppWithPrevPermit = true;

      ajax.query("user-organizations-for-archiving-project")
        .processing(self.processing)
        .pending(self.pending)
        .success(function(data) {
          self.organizationOptions(data.organizations);
          if (self.organizationOptions().length) {
            self.selectedPrevPermitOrganization(self.organizationOptions()[0].id);
          }
        })
        .call();
    };

    function trimBackendId() {
      // Remove all the extra whitespace
      // "   hii  haa hoo hee  " -> "hii haa hoo hee"
      return _.trim((self.kuntalupatunnusFromPrevPermit() || "" )).split(/\s+/).join( " ");
    }

    self.createArchivingProject = function(manually) {
      var params = self.locationModel.toJS();
      params.lang = loc.getCurrentLanguage();
      params.organizationId = self.selectedPrevPermitOrganization();
      params.kuntalupatunnus = trimBackendId();
      params.createAnyway = manually;

      ajax.command("create-archiving-project", params)
        .processing(self.processing)
        .pending(self.pending)
        .success(function(data) {
          self.clear();
          pageutil.openApplicationPage({id: data.id});
        })
        .error(function(d) {
          if (d.needMorePrevPermitInfo) {
            if (self.needMorePrevPermitInfo()) {
              notify.error(loc("error.dialog.title"), loc("info.no-previous-permit-found-from-backend"));
            } else {
              self.needMorePrevPermitInfo(true);
            }
          } else if (d.permitNotFound) {
            self.permitNotFound(true);
            self.locationModel.clearMap();
          } else {
            notify.ajaxError(d);
          }
        })
        .call();
    };
  }

  var model = new CreateApplicationModel();

  hub.onPageLoad("create-archiving-project", model.initCreateAppWithPrevPermit);

  function initAutocomplete(id) {
    $(id)
      .keypress(function(e) { if (e.which === 13) { model.searchNow(); }}) // enter
      .autocomplete({
        source:     "/proxy/find-address?lang=" + loc.getCurrentLanguage(),
        delay:      500,
        minLength:  3,
        select:     model.autocompleteSelect
      })
      .data("ui-autocomplete")._renderItem = model.autocompleteRender;
  }

  $(function() {
    $("#create-archiving-project").applyBindings(model);
    initAutocomplete("#archiving-address-search");

  });

})();
