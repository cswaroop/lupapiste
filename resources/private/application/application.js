/**
 * application.js:
 */

;(function() {
  
  var applicationViewModel;

//hub.whenOskariMapIsReady(function() {
//hub.moveOskariMapToDiv("application-map");
//refreshMap();
//});

//var applicationMap;
//var markers = new OpenLayers.Layer.Markers( "Markers" );
//
//var marker;
//var icon = (function() {
//  var size = new OpenLayers.Size(21,25);
//  var offset = new OpenLayers.Pixel(-(size.w/2), -size.h);
//  return new OpenLayers.Icon('/img/marker-green.png', size, offset);
//})();

//  function refreshMap() {
//    // refresh map for applications
//    hub.clearMapWithDelay(refreshMapPoints);
//  }

//  function refreshMapPoints() {
//    // FIXME Hack: we'll have to wait 100ms
//    setTimeout(function() {
//      var mapPoints = [];
//
//      mapPoints.push({
//        id: "markerFor" + application.id(),
//        location: {x: application.location().lon(), y: application.location().lat()}
//      });
//
//      hub.send("documents-map", {
//        data : mapPoints
//      });
//    }, 99);
//    
//  }

  function ApplicationViewModel() {
    var self = this;
    
    self.data = ko.observable();
    
    self.auth = ko.computed(function() {
      var value = [];
      if(self.data() != undefined) {
        var auth = ko.utils.unwrapObservable(self.data().auth());
        var pimped = _.reduce(auth, function(r, i) { var a = r[i.id()] || (i.roles = [], i); a.roles.push(i.role()); r[i.id()] = a; return r;}, {});
        value = _.values(pimped);
      } 
      return value;
    },self);
  }
  
  var application = {
    id: ko.observable(),
    state: ko.observable(),
    location: ko.observable(),
    permitType: ko.observable(),
    title: ko.observable(),
    created: ko.observable(),
    documents: ko.observable(),
    attachments: ko.observableArray(),
    comments: ko.observable(),
    address: ko.observable(),
    verdict: ko.observable(),

    // new stuff
    invites: ko.observableArray(),
    
    // all data in here
    data: ko.observable(),
    
    submitApplication: function(model) {
      var applicationId = application.id();
      ajax.command("submit-application", { id: applicationId})
      .success(function(d) {
        notify.success("hakemus j\u00E4tetty",model);
        repository.reloadAllApplications();
      })
      .call();
      return false;
    },

    approveApplication: function(model) {
      var applicationId = application.id();
      ajax.command("approve-application", { id: applicationId})
        .success(function(d) {
          notify.success("hakemus hyv\u00E4ksytty",model);
          repository.reloadAllApplications();
        })
        .call();
      return false;
    },

    removeInvite : function(model) {
      var applicationId = application.id();
      ajax.command("remove-invite", { id : applicationId, email : model.user.username()})
        .success(function(d) { 
          notify.success("kutsu poistettu", model);
          repository.reloadAllApplications();
        })
        .call();
      return false;
    },
    
    removeAuth : function(model) {
      console.log(model);
      var applicationId = application.id();
      ajax.command("remove-auth", { id : applicationId, email : model.username()})
        .success(function(d) { 
          notify.success("oikeus poistettu", model);
          repository.reloadAllApplications();
        })
        .call();
      return false;
    }

  };
  
  var emptyRh1 = {
    rakennuspaikanTiedot: {
      building_location: "",
      borough: "",
      stead_number: "",
      stead_name: "",
      lot: "",
      building_address: "",
      building_address_second: "",
      postal_code: "",
      post_office: "",
      tenure: "",
      owner: "",
      plan_readiness: "",
      exemption: ""     
    },
    rakennuksenTiedot: {
      ownership: "",
      ownership_other: "",
      builder: "",
      operation: "",
      main_use: "",
      building_volume: "",
      building_gross_floor_area: "",
      building_total_area: "",
      floor_count: 0,
      cellar_floor_area: "",
      supporting_structure_material: "",
      supporting_structure_material_other: "",
      build_style: "",
      facade_material: "",
      facade_material_other: "",
      network_interfaces: {
        sewer: false,
        water: false,
        electricity: false,
        gas: false,
        cabel: false
      },
      heating: "",
      heating_source: "",
      heating_source_other: "",
      building_equipments: {
                electricity: false,
                gas: false,
                sewer: false,
                water: false,
                warm_water: false,
                solar_panel: false,
                elevator: false,
                air_conditioning: false,
                saunas: false,
                swimming_pools: false,
                shelter: false
      },
      aaa: {
        a1: "",
        a2: "",
        a3: "",
        a4: "",
        a5: "",
        a6: "",
        a7: "",
        a8: "",
        a9: "",
        a10: ""
      }
    },
    huoneistonTiedot: {
      apartments: "",
      apartment_id: "",
      room_count: 0,
      kitchen_type: "",
      apartment_floor_area: "",
      apartment_equipments: {
        toilet: false,
        shower: false,
        sauna: false,
        balcony: false,
        warm_water: false
      },
      new_apartments: "",
      apartments_total_floor_area: ""
    },
    save: function(m) {
      var u = ko.mapping.toJS(m);
      delete u.save;
      ajax
        .command("rh1-demo", {id: application.id(), data: u})
        .success(function() { debug("RH1 save completed"); })
        .error(function() { error("RH1 save failed"); })
        .call();
      return false;
    }
  };
  
  var rh1 = ko.mapping.fromJS(emptyRh1);
  
  var authorization = {
    data: ko.observable({})
  };

  function makeSubscribable(initialValue, listener) {
    var v = ko.observable(initialValue);
    v.subscribe(listener);
    return v;
  }
 
  function showApplication(data) {
    ajax.query("allowed-actions",{id: data.id})
      .success(function(d) {
        authorization.data(d.actions);
        showApplicationPart2(data);
        hub.setPageReady("application");
      })
      .call();
  }

  function showApplicationPart2(data) {
    
    // new data mapping
    applicationViewModel.data(ko.mapping.fromJS(data));

    //function f(r, i) { var u = r[i.id] || (i.roles = [], i); u.roles.push(i.role); r[i.id] = u; return r;}
    //_.reduce(a, f, {});

    ko.mapping.fromJS(data, {}, application);
    ko.mapping.fromJS(data.rh1 || emptyRh1, rh1);
    
    application.attachments.removeAll();
    var attachments = data.attachments;
    if (attachments) {
      for (var attachmentId in attachments) {
        var attachment = attachments[attachmentId];
        attachment.open = "window.location.hash = '!/attachment/" + data.id + "/" + attachment.id + "';";
        application.attachments.push(attachment);
      }
    }
  }
  
  function uploadCompleted(file, size, type, attachmentId) {
    // if (attachments) attachments.push(new Attachment(file, type, size, attachmentId));
  }
    
  hub.subscribe("repository-application-reload", function(e) {
    if (application.id() === e.applicationId) {
      repository.getApplication(e.applicationId, showApplication, function() {
        // TODO: Show "No such application, or not permission"
        error("No such application, or not permission");
      });
    }
  });
      
  var comment = {
    text: ko.observable(),
    submit: function(model) {
    var applicationId = application.id();
      ajax.command("add-comment", { id: applicationId, text: model.text()})
      .success(function(d) { 
        repository.reloadAllApplications();
          model.text("");
        }).call();
    return false;
  }
  };

  comment.disabled = ko.computed( function() { return comment.text() == "" || comment.text() == null; });
  
  var invite = {
    email : ko.observable(),
    title : ko.observable("uuden suunnittelijan lisääminen"),
    text  : ko.observable(),
    submit: function(model) {
      ajax.command("invite", { id: application.id(),
                               email: model.email(),
                               title: model.title(),
                               text: model.text()})
        .success(function(d) {
          model.email(undefined);
          model.text(undefined);
          repository.reloadAllApplications();
        })
        .error(function(d) {
          notify.info("kutsun lähettäminen epäonnistui",d);
        })
        .call();
      return false;
    }
  }

    var tab = {
        tabClick: function(data, event) {
           var self = event.target;
           $("#tabs li").removeClass('active');
           $(self).parent().addClass("active");
           $(".tab_content").hide();
           var selected_tab = $(self).attr("href");
           $(selected_tab).fadeIn();
        }
    };

    var accordian = {
        accordianClick: function(data, event) {
           self = event.target;
           $(self).next(".application_section_content").toggleClass('content_expanded');
        }
    };
      
  function onPageChange(e) {
    var id = e.pagePath[0];
    if (application.id() != id) {
      repository.getApplication(id, showApplication, function() {
        // TODO: Show "No such application, or not permission"
        error("No such application, or not permission");
      });
    }
    
  }
  
  $(function() {
    hub.subscribe({type: "page-change", pageId: "application"}, function(e) {
      onPageChange(e);
    });
    
    applicationViewModel = new ApplicationViewModel();
    
    var page = $("#application");

    ko.applyBindings({
      application : application,
      applicationModel : applicationViewModel,
      comment : comment,
      invite : invite,
      authorization : authorization,
      rh1 : rh1,
      tab : tab,
      accordian : accordian
    }, page[0]);

    initUpload($(".dropbox", page), function() { return application.id(); }, uploadCompleted);
  });

})();
