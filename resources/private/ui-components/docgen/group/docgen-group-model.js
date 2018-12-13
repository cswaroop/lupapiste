LUPAPISTE.DocgenGroupModel = function(params) {
  "use strict";
  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel(params));

  self.path = _.isArray(params.path) ? params.path : [params.path];
  self.applicationId = params.applicationId;
  self.documentId = params.documentId;
  self.service = params.service || lupapisteApp.services.documentDataService;
  self.schemaRows = _.map(params.schema.rows,
                          function( row ) {
                            if( _.isArray( row )) {
                              return {row: row};
                            }
                            if( row.row ) {
                              return {row: row.row,
                                      rowCss: util.arrayToObject( row.css )};
                            }
                            return row;
                          });
  self.authModel = params.authModel || lupapisteApp.models.applicationAuthModel;
  self.componentTemplate = (params.template || params.schema.template) || "default-docgen-group-template";

  self.groupId = ["group", params.documentId].concat(self.path).join("-");
  self.groupLabel = params.i18npath.concat("_group_label").join(".");
  self.groupHelp = params.schema["group-help"] && params.i18npath.concat(params.schema["group-help"]).join(".");

  self.indicator = ko.observable().extend({notify: "always"});
  self.result = ko.observable().extend({notify: "always"});
  self.css = _.reduce( params.schema.css || [],
                       function( acc, cls ) {
                         return _.set( acc, cls, true );
                       },
                       {});

  function getValueByPathString(groupPath, pathString, documentName) {
    var path = pathString.split("/");
    var docId = self.documentId;
    var absolutePath = null;
    if( documentName ) {
      docId = self.service.findDocumentByName( documentName ).id;
      absolutePath = _.concat( [documentName], path );
    } else {
      absolutePath = path[0] === "" ? _.tail(path) : groupPath.concat(path);
    }
    return util.getIn(self.service.getInDocument(docId, absolutePath), ["model"]);
  }

  self.subSchemas = self.disposedPureComputed(function() {
    return _(params.schema.body)
      .reject(function(schema) {
        var hideWhen = schema["hide-when"];
        return hideWhen
          ? _.includes(hideWhen.values, getValueByPathString(self.path,
                                                             hideWhen.path,
                                                             hideWhen.document))
          : false;
      })
      .filter(function(schema) {
        var showWhen = schema["show-when"];
        return showWhen
          ? _.includes(showWhen.values, getValueByPathString(self.path,
                                                             showWhen.path,
                                                             showWhen.document))
          : true;
      })
      .filter(function(schema) {
        return self.service.getInDocument(self.documentId, self.path.concat(schema.name));
      })
      .map(function(schema) {
        var uicomponent = schema.uicomponent || "docgen-" + schema.type;
        var i18npath = schema.i18nkey ? [schema.i18nkey] : params.i18npath.concat(schema.name);
        return _.extend({}, schema, {
          path: self.path.concat(schema.name),
          uicomponent: uicomponent,
          schemaI18name: params.schemaI18name,
          i18npath: i18npath,
          applicationId: params.applicationId,
          documentId: params.documentId,
          service: self.service
        });
      }).value();
  });

  function hideSchema(schema, parentPath) {
    var hideWhen = _.get(schema, "hide-when");
    var showWhen = _.get(schema, "show-when");
    return hideWhen
      && _.includes(hideWhen.values, getValueByPathString(parentPath,
                                                          hideWhen.path,
                                                          hideWhen.document))
      || showWhen
      && !_.includes(showWhen.values, getValueByPathString(parentPath,
                                                           showWhen.path,
                                                           showWhen.document));
  }

  function getInSchema(schema, schemaPath, path) {
    var pathArray = _.isArray(path) ? path : path.split("/");
    if (hideSchema(schema, _.dropRight(schemaPath))) {
      return null;
    } else if (_.isEmpty(pathArray)) {
      return schema;
    } else {
      var schemaName = _.head(pathArray);
      return getInSchema(_.find(schema.body, {name: schemaName}), schemaPath.concat(schemaName), _.tail(pathArray));
    }
  }

  function parsePart( s, re, defaultValue ) {
    return _.last( re.exec( s )) || defaultValue;
  }

  self.rowSchemas = self.disposedPureComputed(function() {
    return _(self.schemaRows)
      .map(function(row) {
        if ( row.row) {
          return {row: _(row.row )
                  .map(function(schemaName) {
                    var cols = parsePart( schemaName, /::([\d+])/, 1);
                    var colClass = ["col-" + cols, parsePart( schemaName, /\[([^\]]+)\]/)].join( " ");
                    var pathString = parsePart( schemaName, /^[^\[:]+/ );
                    var path = self.path.concat(pathString.split("/"));
                    var schema = getInSchema(params.schema, self.path, pathString);
                    return schema && _.extend({}, schema, {
                      path: path,
                      uicomponent: schema.uicomponent || "docgen-" + schema.type,
                      schemaI18name: params.schemaI18name,
                      i18npath: schema.i18nkey ? [schema.i18nkey] : params.i18npath.concat(pathString.split("/")),
                      applicationId: params.applicationId,
                      documentId: params.documentId,
                      service: self.service,
                      colClass: colClass
                    });
                  })
                  .filter()
                  .value(),
                  rowCss: row.rowCss};
        } else {
          var headerTag = _.head(_.keys(row));
          var ltext = row[headerTag];
          return {ltext: ltext, css: _.fromPairs( [[headerTag, true]])};
        }
      })
      .reject(_.isEmpty)
      .value();
  });

};
