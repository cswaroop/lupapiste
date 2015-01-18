var stamping = (function() {
  "use strict";

  var model = {
    stampingMode: ko.observable(false),
    authorization: null,
    appModel: null,
    attachments: null,
    stampFields: {
      text: ko.observable(loc("stamp.verdict")),
      date: ko.observable(new Date()),
      organization: null,
      xMargin: ko.observable("10"),
      yMargin: ko.observable("85"),
      transparency: ko.observable(),
      extraInfo: ko.observable(""),
      buildingId: ko.observable(""),
      kuntalupatunnus: ko.observable(""),
      section: ko.observable(""),
      buildingIdList: ko.observableArray()
    },

    cancelStamping: function() {
      model.stampingMode(false);
      var id = model.appModel.id();
      model.appModel = null;
      model.attachments = null;
      model.authorization = null;

      window.location.hash="!/application/" + id + "/attachments";
      repository.load(id);
    },

    resetStamping: function() {
      model.stampingMode(false);
      model.appModel = null;
      model.attachments = null;
      model.authorization = null;

      hub.send("page-change", { pageId: "stamping" });
    }
  };

  function setStampFields() {
    if ( !model.stampFields.organization ) {
      model.stampFields.organization = ko.observable(model.appModel.organizationName());
    }

    if ( model.appModel.verdicts && !_.isEmpty(model.appModel.verdicts()) ) {
      // stampFields.kuntalupatunnus
      model.stampFields.kuntalupatunnus(_.first(model.appModel.verdicts()).kuntalupatunnus());
      // stampFields.section
      var verdict = ko.mapping.toJS(model.appModel.verdicts()[0]);
      if ( verdict.paatokset[0] && verdict.paatokset[0].poytakirjat[0] && verdict.paatokset[0].poytakirjat[0].pykala ) {
        model.stampFields.section(verdict.paatokset[0].poytakirjat[0].pykala);
      } else {
        model.stampFields.section("");
      }
    } else {
      model.stampFields.kuntalupatunnus("");
      model.stampFields.section("");
    }

    if ( model.appModel.buildings ) {
      model.stampFields.buildingIdList(model.appModel.buildings());
    }
  }

  function initStamp(appModel) {
    model.appModel = appModel;
    model.attachments = model.appModel.attachments();
    model.authorization = authorization.create();
    model.authorization.refresh(model.appModel.id());

    setStampFields();

    window.location.hash="!/stamping/" + model.appModel.id();
  }

  hub.onPageChange("stamping", function() {
    if ( pageutil.subPage() ) {
      if ( !model.appModel || model.appModel.id() !== pageutil.subPage() ) {
        // refresh
        model.stampingMode(false);

        var appId = pageutil.subPage();
        repository.load(appId, null, function(application) {
          model.authorization = authorization.create();
          model.appModel = new LUPAPISTE.ApplicationModel();
          model.authorization.refresh(application);

          ko.mapping.fromJS(application, {}, model.appModel);

          model.attachments = model.appModel.attachments();

          setStampFields();

          model.stampingMode(true);
        });
      } else { // appModel already initialized, show stamping
        model.stampingMode(true);
      }
    } else {
      error("No application ID provided for stamping");
      LUPAPISTE.ModalDialog.open("#dialog-application-load-error");
    }
  });

  hub.subscribe("start-stamping", function(param) {
    initStamp(param.application);
  });

  ko.components.register("stamping-component", {
    viewModel: LUPAPISTE.StampModel,
    template: {element: "stamp-attachments-template"}
  });

  $(function() {
    $("#stamping-container").applyBindings(model);
  });
})();
