LUPAPISTE.ModalDialogModel = function () {
  "use strict";
  var self = this;

  self.showDialog = ko.observable(false);
  self.component = ko.observable();
  self.componentParams = ko.observable();
  self.extraClass = ko.observable();
  self.windowWidth = ko.observable();
  self.windowHeight = ko.observable();
  self.dialogVisible = ko.observable(false);
  self.title = ko.observable();

  self.showDialog.subscribe(function(show) {
    _.delay(function(show) {
      self.dialogVisible(show);
    }, 100, show);
  });

  self.dialogWidth = ko.pureComputed(function() {
    return self.windowWidth() - 200;
  });

  self.dialogHeight = ko.pureComputed(function() {
    return self.windowHeight() - 150;
  });

  self.submitFn = ko.observable(undefined);
  self.submitEnabled = ko.observable();

  self.submitDialog = function() {
    self.submitFn()();
    self.closeDialog();
  };

  self.closeDialog = function() {
    self.showDialog(false);
    $("html").removeClass("no-scroll");
  };

  hub.subscribe("show-dialog", function(data) {
    $("html").addClass("no-scroll");
    self.component(data.component);
    var componentParams = data.componentParams ? data.componentParams : {};
    // self.componentParams(_.assign(componentParams,
    //   {submitFn: self.submitFn,
    //    submitEnabled: self.submitEnabled}));
    self.componentParams(componentParams);
    self.title = data.title;
    self.extraClass(data.extraClass);
    self.showDialog(true);
  });

  hub.subscribe("close-dialog", function() {
    self.closeDialog();
  });

  var setWindowSize = function(width, height) {
    self.windowWidth(width);
    self.windowHeight(height);
  };

  var win = $(window);
  // set initial dialog size
  setWindowSize(win.width(), win.height());

  // listen widow change events
  win.resize(function() {
    setWindowSize(win.width(), win.height());
  });
};
