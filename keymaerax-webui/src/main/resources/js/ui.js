/*
 * The UI object contains helper methods for maniuplating top-level elements
 * in the user interface; specifically:
 *    the error log
 *    the server status indicator
 *    the proof window
 * 
 * For example, UI should not contain methods for manipulating views of a
 * proof; those should go in the view's class or object.
 *
 * Nathan Fulton 2014
 */
var UI = {
  getErrorElement: function() {
    return document.getElementById("errors")
  },
  getStatusElement: function() {
    return document.getElementById("status")
  },
  getMainElement: function() {
    return document.getElementById("main")
  },


  /** @param isOnline - true if the server is up; false otherwise. */
  updateStatusDisplay: function(isOnline) {
    this.getStatusElement().innerHTML = 
      "hydra://" + ServerInfo.hostname + ":" + ServerInfo.port
    var color;
    if(isOnline) { color = "006600"; } else { color = "00FF00"; }
    this.getStatusElement().style.backgroundColor = color;
  },

  /**
   * @param msg - an english message presented to the user.
   * @param exn - an object -- hopefully a stack trace -- printed to the
   * console.
   */
  showError: function(msg, exn) {
    this.updateStatusDisplay(false)
    var report = document.createElement("div");
    report.innerHTML = msg;
    this.getErrorElement().appendChild(report);
    console.error("User Inferface reported error: " + msg);
    console.error(exn);
  },
}

