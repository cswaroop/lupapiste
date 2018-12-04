LUPAPISTE.DocgenInputModel = function(params) {
  "use strict";
  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel(params));

  self.authModel = params.authModel || lupapisteApp.models.applicationAuthModel;

  var service = params.service || lupapisteApp.services.documentDataService;

  self.componentTemplate = (params.template || params.schema.template)
                           || "docgen-" + (params.schema.inputType || params.schema.type) + "-template";

  self.size = uiComponents.sizeClasses[params.schema.size];
  self.schema = params.schema;
  self.path = params.path;
  self.documentId = params.documentId || params.schema.documentId;
  self.model = service.getInDocument(self.documentId, params.path);
  self.value = self.model.model;
  self.schemaCss = _.compact( _.concat(self.schema.css,
                                       self.schema.layout === "full-width"
                                       ? "layout--full-width" : null)).join( " " );

  self.i18npath = params.schema.i18nkey ? [params.schema.i18nkey] : params.schema.i18npath;
  if (!self.i18npath) {
    self.i18npath = [util.locKeyFromDocPath([params.schemaI18name].concat(params.path).join("."))];
  }

  self.label = (params.schema.label === false || params.schema.label === "false") ? null : self.i18npath.join(".");

  self.indicator = ko.observable().extend({notify: "always"});

  self.result = self.disposedPureComputed(function() {
    var myDoc = service.findDocumentById(self.documentId);
    var validation = _.find(myDoc.validationResults(), function(validation) {
      return _.isEqual(validation.path, self.path);
    });
    return validation && validation.result;
  });

  self.errorMessage = self.disposedPureComputed(function() {
    var errType = self.result() && self.result()[0];
    var message = errType && errType !== "tip" && loc(["error", self.result()[1]]);
    return message;
  });

  self.showMessagePanel = ko.observable(false);
  self.events = {
    mouseover: function() { self.showMessagePanel(true); },
    mouseout: function() { self.showMessagePanel(false); }
  };

  self.helpMessage = ko.observable();

  var helpLocKey = self.i18npath.concat("help").join(".");

  if (loc.hasTerm(helpLocKey)) {
    self.helpMessage(loc(helpLocKey));
  }

  function defaultInputClasses() {
    var typeDefaults = {select: "form-input combobox",
                        string: "form-input",
                        time: "form-input",
                        text: "form-input textarea",
                        "inline-string": "form-input inline",
                        "localized-string": "form-string",
                        "check-string": "check-string",
                        "paragraph" : "form-paragraph",
                        "date": "form-input dateinput",
                        "checkbox-wrapper": "form-input"};
    return _.get( typeDefaults, (self.schema.inputType || self.schema.type), "");
  }

  self.signalClasses = self.disposedComputed(function() {
    var classes = [];
    var result = self.result() ? self.result()[0] : undefined;
    if (result) {
      classes.push(result);
    }
    classes.push(self.size);
    return classes.join(" ");
  });

  self.labelClasses = self.disposedComputed(function() {
    return "form-label " + self.signalClasses();
  });

  self.inputClasses = self.disposedComputed(function() {
    return (self.schemaCss || defaultInputClasses()) + " " + self.signalClasses();
  });

  self.readonly = self.disposedPureComputed(function () {
    var doc = service.findDocumentById( self.documentId );
    var readOnlyAfterSent = params.schema["readonly-after-sent"] && (doc.state === "sent" || doc.state === "faulty_review_task");
    return readOnlyAfterSent || params.schema.readonly || params.readonly;
  });
  self.inputOptions = {maxLength: params.schema["max-len"] || LUPAPISTE.config.inputMaxLength,
                       max: params.schema.max,
                       min: params.schema.min,
                       placeholder: loc( params.schema.placeholder || "empty")};

  function authState( state ) {
    var commands = _.get( self.schema.auth, state );
    if( _.isArray( commands) && _.size( commands )) {
      return _.some( commands, self.authModel.ok );
    }
  }

  self.disabled = self.disposedPureComputed( function() {
    var disabled = params.isDisabled
          || !(service.isWhitelisted( self.schema ))
          || !self.authModel.ok(service.getUpdateCommand(self.documentId))
          || util.getIn(params, ["model", "disabled"]);
    var authDisabled = authState( "disabled" );
    if( _.isBoolean( authDisabled ) ) {
      disabled = disabled || authDisabled;
    }
    var authEnabled = authState( "enabled" );
    if( _.isBoolean( authEnabled ) ) {
      disabled = disabled || !authEnabled;
    }
    return disabled;
  });

  var latestSaved = self.value();

  var save = function(val) {
    if( latestSaved !== val ) {
      service.updateDoc(self.documentId,
                        [[params.path, val]],
                        self.indicator);
      latestSaved = val;
    }
  };

  var baseDispose = self.dispose;

  self.dispose = function() {
    save( self.value());
    baseDispose();
  };

  self.disposedSubscribe(self.value, _.debounce(save, 500));
};
