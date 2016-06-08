LUPAPISTE.ReservationSlotCreateBubbleModel = function( params ) {
  "use strict";
  var self = this;

  ko.utils.extend(self, new LUPAPISTE.ComponentBaseModel(params));

  self.startTime = ko.observable();
  self.positionTop = ko.observable();
  self.weekdayCss = ko.observable();
  self.calendarId = lupapisteApp.services.calendarService.calendarQuery.calendarId;
  self.reservationTypes = lupapisteApp.services.calendarService.calendarQuery.reservationTypes;
  self.selectedReservationTypes = ko.observableArray();
  self.amount = ko.observable();
  self.maxAmount = ko.observable();

  self.waiting = params.waiting;
  self.error = ko.observable(false);
  self.bubbleVisible = ko.observable(false);

  self.okEnabled = self.disposedComputed(function() {
    var amount = _.toInteger(self.amount());
    var isValid = amount > 0 && !_.isEmpty(self.selectedReservationTypes());
    return !self.error() && isValid;
  });

  self.send = function() {
    if (self.amount() > self.maxAmount()) {
      self.error("calendar.error.cannot-create-overlapping-slots");
      return;
    }
    var slots = _.map(_.range(self.amount()), function(d) {
      var t1 = moment(self.startTime()).add(d, "h");
      var t2 = moment(self.startTime()).add(d+1, "h");
      return {
        start: t1.valueOf(),
        end: t2.valueOf(),
        reservationTypes: self.selectedReservationTypes()
      };
    });
    self.sendEvent("calendarService", "createCalendarSlots", {calendarId: self.calendarId(), slots: slots});
    self.bubbleVisible(false);
  };

  self.init = function() {
  };

  function maxFreeTimeAfterGivenTime(weekday, timestamp) {
    var laterSlots = _.filter(weekday.slots, function(slot) { return slot.startTime > timestamp; });
    var nextSlotStartTime = laterSlots[0] ? laterSlots[0].startTime : weekday.endOfDay;
    return moment(nextSlotStartTime).diff(timestamp, "seconds");
  }

  self.addEventListener("calendarView", "timelineSlotClicked", function(event) {
    var weekday = event.weekday;
    var hour = event.hour;
    var minutes = event.minutes;
    var timestamp = moment(weekday.startOfDay).hour(hour).minutes(minutes);

    self.error(false);
    // can not create slots to the past
    if (timestamp.isBefore(moment())) {
      self.error("calendar.error.slot-in-past");
    }

    self.startTime(timestamp);
    self.positionTop((event.hour - params.tableFirstFullHour + 1) * 60 + "px");
    self.weekdayCss("weekday-" + timestamp.isoWeekday());
    self.selectedReservationTypes([]);
    self.amount(1);
    self.maxAmount(maxFreeTimeAfterGivenTime(weekday, timestamp.valueOf()) / 3600);
    self.bubbleVisible(true);
  });

};