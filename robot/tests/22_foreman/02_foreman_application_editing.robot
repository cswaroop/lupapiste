*** Settings ***

Resource        ../../common_resource.robot
Resource        ../common_keywords/approve_helpers.robot
Resource        keywords.robot
Suite Setup     Initialize foreman

*** Keywords ***
Sonja creates an application and invites foreman
  # Create application directly to submitted state to speed up the test
  Create project application  submitted
  Sonja invites foreman to application

Open linking dialog
  Click enabled by test id  application-add-link-permit-btn
  Wait until  Element should be visible by test id  add-link-permit-card
  Wait Until  Element should be visible  xpath=//div[@data-test-id='link-permit-select']

*** Test Cases ***
Sonja inits applications
  Sonja logs in
  Sonja creates an application and invites foreman
  Sonja creates an application and invites foreman
  Logout

Foreman fills personal information
  Foreman logs in
  Foreman applies personal information to the foreman application  0
  Check accordion text  tyonjohtaja-v2  TYÖNJOHTAJAN NIMEÄMINEN  - Teppo Nieminen

Foreman can not fill applicant information
  # No inputs that are missing a readoly attribute. In other words, all inputs are read only.
  Xpath Should Match X Times  //section[@data-doc-type='hakija-tj']//input[not(@readonly)]  0

Foreman selects application type and submits the first foreman application
  Select From List By Value  permitSubtypeSelect  tyonjohtaja-hakemus
  Positive indicator should be visible

  Submit application

Foreman cannot see related projects
  Foreman applies personal information to the foreman application  1
  No such test id  'muutHankkeet.1.luvanNumero'

Foreman gets error message when trying to submit foreman notice before link permit has verdict
  Select from list by value  permitSubtypeSelect  tyonjohtaja-ilmoitus
  Open tab  requiredFieldSummary
  Element should be disabled  xpath=//button[@data-test-id='application-submit-btn']
  Submit application error should be  error.foreman.notice-not-submittable
  Wait Until  Application state should be  open

Foreman application can be submitted
  Select From List By Value  permitSubtypeSelect  tyonjohtaja-hakemus
  Positive indicator should be visible
  Submit application

Foreman can comment foreman application
  Add comment  Hakemus on nyt jätetty

Foreman could only add guests to foreman application
  Open tab  parties
  Wait test id visible  application-guest-add
  Element should not be visible by test id  application-invite-person

Foreman can not add parties to foreman application
  Element should not be visible by test id  hakija-tj_append_btn
  Element should not be visible by test id  add-party

Foreman could add attachment to foreman application
  Open tab  attachments
  Element should be visible by test id  add-attachments-label

Foreman only read comments on project application
  Open project application
  Confirm yes no dialog
  Open side panel  conversation
  Element should not be visible by test id  application-new-comment-text
  Element should not be visible by test id  application-new-comment-btn

Foreman can not invite anyone to the project application
  Open accordions  parties
  Element should not be visible by test id  application-invite-person
  Element should not be visible by test id  application-guest-add

Foreman can not add parties to the project application
  Element should not be visible by test id  add-party

Foreman could add attachment to project application
  Open tab  attachments
  Element should be visible by test id  add-attachments-label
  Logout

Sonja logs in and gets verdict for the first foreman application
  Sonja logs in
  Verdict for foreman application  0
  Logout

Foreman logs in and checks related projects on the second foreman application
  Foreman logs in
  Open foreman application  1
  Open tab  parties
  Scroll and click test id  fill-info-button
  Wait for jQuery
  Check related project  0
  Logout

#Can not link base app to foreman application
#  Open project application
#  Open linking dialog
#
#  ${app} =   Get From List  ${applications}  1
#  ${linkPermitAppId} =   Get From List  ${foremanApps}  1
#
#  Select from autocomplete by test id  link-permit-select  ${app}, ${linkPermitAppId}
#  Autocomplete selection by test id is  link-permit-select  ${app}, ${linkPermitAppId}
#  Click enabled by test id  button-link-permit-dialog-add
#  Wait Until  Element should be visible  xpath=//div[@data-test-id="add-link-permit-card"]//div[@data-bind="ltext: errorMessage"]
#  Element Text Should Be  xpath=//div[@data-test-id="add-link-permit-card"]//div[@data-bind="ltext: errorMessage"]  Kohdehakemukseen ei voi lisätä enempää viitteitä
#  Logout

Authority opens the submitted foreman application
  Sonja logs in
  Open foreman application  1
  Wait until  Application state should be  submitted

Authority decides that a verdict is not required
  Select From List By Value  permitSubtypeSelect  tyonjohtaja-ilmoitus
  Positive indicator should be visible

Authority tries to send application to backend
  Open tab  requiredFieldSummary
  Click enabled by test id  approve-application-summaryTab

Can not be send before base app
  Confirm  integration-error-dialog

Approve base app
  Go back to project application
  Approve application

Fetch verdict to base app
  Open tab  verdict
  Fetch verdict

Approve foreman app
  Open foreman application  1
  Open tab  requiredFieldSummary
  Click enabled by test id  approve-application-summaryTab
  Wait Until  Application state should be  acknowledged

Link foreman approval to base app
  Go back to project application
  Wait Until  Application state should be  verdictGiven
  Open tab  tasks

  Required foreman state is  Vastaava työnjohtaja  missing

  ${foremanAppId} =  Get From List  ${foremanApps}  1
  Focus  xpath=//select[@data-test-id="foreman-selection-0"]
  Select From List By Value  xpath=//tr[@data-test-name="Vastaava työnjohtaja"]//select[@data-test-id="foreman-selection-0"]  ${foremanAppId}

  Wait Until  Required foreman state is  Vastaava työnjohtaja  ok

On second thought, complement is needed
  Open foreman application  1
  Click enabled by test id  request-for-complement
  Wait Until  Application state should be  complementNeeded

Foreman state has reset on base app
  Go back to project application
  Wait Until  Application state should be  verdictGiven
  Open tab  tasks
  Wait Until  Required foreman state is  Vastaava työnjohtaja  new

Change subtype back to foreman application
  Open foreman application  1
  Wait until  Select from list by value  permitSubtypeSelect  tyonjohtaja-hakemus
  Positive indicator should be visible

Verdict could be not given in complementNeeded (LPK-2559)
  Open tab  verdict
  Wait Until  Element should not be visible  //div[@id="application-verdict-tab"]//button[@data-test-id="give-verdict"]

Re-send and give verdict
  Open tab  requiredFieldSummary
  Click enabled by test id  approve-application-summaryTab
  Wait until  Application state should be  sent
  Submit empty verdict  foremanVerdictGiven
  Application state should be  foremanVerdictGiven

Foreman state has changed on base app
  Go back to project application
  Open tab  tasks
  Wait Until  Required foreman state is  Vastaava työnjohtaja  ok

Deleting the verdict sets application back to previous state
  Open foreman application  1
  Open tab  verdict
  Kill dev-box
  Wait Until  Element should be visible  //div[@id="application-verdict-tab"]//*[@data-test-id="delete-verdict-from-listing"]
  # The click below fails if the window has a certain width and the conversation button obscures the delete icon
  Click element  xpath=//div[@id="application-verdict-tab"]//*[@data-test-id="delete-verdict-from-listing"]
  Confirm  dynamic-yes-no-confirm-dialog
  Wait Until  Element should not be visible  //div[@id="application-verdict-tab"]//*[@data-test-id="delete-verdict-from-listing"]

  Application state should be  sent

Foreman state has reset again on base app
  Go back to project application
  Open tab  tasks
  Wait Until  Required foreman state is  Vastaava työnjohtaja  new

Frontend errors
  There are no frontend errors
