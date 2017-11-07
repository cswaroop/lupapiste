LUPAPISTE.verdictPageController = (function($) {
  "use strict";

  var currentApplicationId = null;
  var currentApplication = {}; // initialized to empty map
  var currentVerdictId = null;

  function VerdictEditModel() {
    var self = this;
    self.refreshing = false;

    self.applicationTitle = ko.observable();

    self.processing = ko.observable();

    self.statuses = ko.observable(_.range(1,43)); // 42 different values in verdict in krysp (verdict.clj)

    self.backendId = ko.observable();
    self.draft = ko.observable();
    self.status = ko.observable();
    self.name = ko.observable();
    self.text = ko.observable();
    self.agreement = ko.observable(false);
    self.section = ko.observable();
    self.given = ko.observable();
    self.official = ko.observable();

    self.taskGroups = ko.observable();
    self.signatureTitle = ko.observable("verdict.agreement");

    self.refresh = function(application, verdictId) {
      self.refreshing = true;

      self.applicationTitle(application.title);

      if (application.permitType === "YA") {
        self.statuses([1,2,21,37]);
        self.signatureTitle("verdict.create.agreement");
      }

      var verdict = _.find((application.verdicts || []), function (v) {return v.id === verdictId;});
      if (verdict) {
        var paatos = verdict.paatokset[0];
        var pk = paatos.poytakirjat[0];
        var dates = paatos.paivamaarat;

        self.backendId(verdict.kuntalupatunnus);
        self.draft(verdict.draft);
        // LPK-1765: Unset status must be undefined,
        // null triggers submit event during <select> initialization.
        self.status(pk.status || undefined);
        self.name(pk.paatoksentekija);
        self.given(dates.anto && new Date(dates.anto));
        self.official(dates.lainvoimainen && new Date(dates.lainvoimainen));
        self.text(pk.paatos);
        self.agreement(verdict.sopimus);
        self.section(pk.pykala);
      } else {
        history.back();
        repository.load(application.id);
      }

      var tasks = _.filter(application.tasks || [], function(task) {
        return _.isEqual(task.source, {"type":"verdict", id: currentVerdictId});
      });

      var schemaInfos = _.reduce(tasks, function(m, task) {
        var info = task.schema.info;
        m[info.name] = info;
        return m;
      },{});

      var groups = _.groupBy(tasks, function(task) {return task.schema.info.name;});

      self.taskGroups(_(groups)
        .keys()
        .map(function(n) {
          return {
            name: loc([n, "_group_label"]),
            order: schemaInfos[n].order,
            tasks: _.map(groups[n], function(task) {
              task.displayName = taskUtil.shortDisplayName(task);
              task.deleteTask = function() {
                LUPAPISTE.ModalDialog.showDynamicYesNo(
                    loc("areyousure"),
                    loc("task.delete.confirm"),
                    {title: loc("yes"), fn: function() {
                      ajax
                        .command("delete-task", {id: currentApplicationId, taskId: task.id})
                        .success(function(){repository.load(currentApplicationId);})
                        .call();}},
                        {title: loc("no")});
                return false;
              };
              task.statusName = LUPAPISTE.statuses[task.state] || "unknown";
              return task;
            })};})
        .sortBy("order")
        .valueOf());

      self.refreshing = false;
    };

    self.returnToApplication = function() {
      repository.load(currentApplicationId);
      pageutil.openApplicationPage({id:currentApplicationId}, "verdict");
      hub.send("indicator-icon", {clear: true});
    };

    self.save = function(onSuccess) {
      if (!self.refreshing) {
        var givenMillis = new Date(self.given()).getTime();
        var officialMillis = new Date(self.official()).getTime();
        ajax
        .command("save-verdict-draft",
            {id: currentApplicationId, verdictId: currentVerdictId,
             backendId: self.backendId(), status: self.status(),
             name: self.name(), text: self.text(),
             section: self.section(),
             agreement: self.agreement() || false,
             given: givenMillis, official: officialMillis})
          .success(onSuccess)
          .processing(self.processing)
          .call();
      }
      return false;
    };

    self.submit = function() {
      self.save(_.partial(hub.send,"indicator-icon", {style: "positive"}));
      return true;
    };

    _.each([self.backendId, self.draft, self.status, self.name, self.text, self.agreement,
            self.section, self.given, self.official],
           function(o) {
             o.subscribe(self.submit);
           }
    );

    self.commandAndBack = function(cmd) {
      ajax
      .command(cmd, {id: currentApplicationId, verdictId: currentVerdictId, lang: loc.getCurrentLanguage()})
      .success(function() {
        self.returnToApplication();
      })
      .processing(self.processing)
      .call();
    };

    self.publish = function() {
      LUPAPISTE.ModalDialog.showDynamicYesNo(loc("areyousure"), loc("verdict.confirmpublish"), {title: loc("yes"), fn: _.partial(self.save, _.partial(self.commandAndBack, "publish-verdict"))});
    };

    self.deleteVerdict = function() {
      LUPAPISTE.ModalDialog.showDynamicYesNo(loc("areyousure"), loc("verdict.confirmdelete"), {title: loc("yes"), fn: _.partial(self.commandAndBack, "delete-verdict")});
    };

    self.disabled = ko.computed(function() {
      return self.processing() || !(self.backendId() && self.status() && self.name() && self.given());
    });
  }

  var verdictModel = new VerdictEditModel();
  var authorizationModel = lupapisteApp.models.applicationAuthModel;
  var targeted = {
    defaults: {
      target: ko.observable(),
      type: ko.observable()
    },
    typeGroups: ko.observableArray(),
    //typeSelector: null,
    dropZoneSectionId: "verdict"};
  var createTaskController = LUPAPISTE.createTaskController;

  function refresh(application, verdictId) {
    var target = {type: "verdict", id: verdictId};
    currentApplication = application;
    currentApplicationId = currentApplication.id;
    currentVerdictId = verdictId;

    lupapisteApp.setTitle(application.title);

    verdictModel.refresh(application, verdictId);

    ajax
      .query("verdict-attachment-type", {id: currentApplicationId})
      .success(function(result) {
        targeted.defaults.target( target );
        targeted.defaults.type( result.attachmentType );
        targeted.typeGroups( [result.attachmentType["type-group"]] );
      })
      .call();

    createTaskController.reset(currentApplicationId, target);
  }

  repository.loaded(["verdict"], function(application) {
    if (currentApplicationId === application.id) {
      refresh(application, currentVerdictId);
    }
  });

  hub.onPageLoad("verdict", function(e) {
    var applicationId = e.pagePath[0];
    var verdictId = e.pagePath[1];
    // Reload application only if needed
    if (currentApplicationId !== applicationId) {
      repository.load(applicationId);
    } else {
      lupapisteApp.setTitle(currentApplication.title);
      if (currentVerdictId !== verdictId){
        refresh(currentApplication, currentVerdictId);
      }
    }
    currentApplicationId = applicationId;
    currentVerdictId = verdictId;
  });

  $(function() {
    $("#verdict").applyBindings({
      verdictModel: verdictModel,
      authorization: authorizationModel,
      createTask: createTaskController,
      targeted: targeted
    });
  });

  return {
    setApplicationModelAndVerdictId: refresh
  };

})(jQuery);
