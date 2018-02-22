*** Settings ***

Documentation   Sonja can't submit application
Suite Setup  Apply minimal fixture now
Suite Teardown  Logout
Resource        ../../common_resource.robot
Variables       ../06_attachments/variables.py

*** Keywords ***

Open test application in required field summary tab
  Open application  ${appname}  ${propertyId}
  Open tab  requiredFieldSummary

Submit button is enabled
  Element should be enabled  xpath=//*[@data-test-id='application-submit-btn']

*** Test Cases ***

Mikko creates a new application
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  submit${secs}
  Set Suite Variable  ${propertyId}  753-416-7-1

  Create application with state  ${appname}  ${propertyId}  kerrostalo-rivitalo  open
  Set Suite Variable  ${attachment-not-needed-test-id-hakija-valtakirja}  attachment-not-needed-hakija-valtakirja
  Set Suite Variable  ${attachment-not-needed-test-id-sonja}  attachment-not-needed-muut-muu

Mikko could submit application (when required fields are not obligatory)
  Open tab  requiredFieldSummary
  Wait Until  Submit button is enabled
  Logout

Sonja can submit application
  Sonja logs in
  Open test application in required field summary tab
  Wait until  Submit button is enabled
  Logout

#
# Testing the missing required fields and attachments
#

Sipoo marks required fields obligatory
  Sipoo logs in
  Go to page  applications
  Checkbox wrapper not selected by test id  required-fields-obligatory-enabled
  Click label by test id  required-fields-obligatory-enabled-label
  Checkbox wrapper selected by test id  required-fields-obligatory-enabled
  Logout

Mikko logs in
  Mikko logs in
  Open application  ${appname}  ${propertyId}

Mikko can not submit application because there are "missing required" items on the requiredFieldSummary tab
  Open tab  requiredFieldSummary
  Wait test id visible  submit-error-0
  Element should be disabled  xpath=//*[@data-test-id='application-submit-btn']
  Element should be visible  xpath=//div[@id='application-requiredFieldSummary-tab']//div[@data-test-id='test-application-warnings']
  Element should be visible  xpath=//div[@id='application-requiredFieldSummary-tab']//div[@data-test-id='test-application-required-fields']
  Element should be visible  xpath=//div[@id='application-requiredFieldSummary-tab']//div[@data-test-id='test-application-required-attachments']
  Xpath Should Match X Times  //div[@id='application-requiredFieldSummary-tab']//div[@data-test-id='test-application-warnings']//*[contains(@class,'info-line')]  12
  ${missingRequiredCount} =  Get Matching Xpath Count  xpath=//*[contains(@class,'info-line')]
  Set Suite Variable  ${missingRequiredCount}
  Logout

Sipoo marks required fields not obligatory
  Sipoo logs in
  Go to page  applications
  Checkbox wrapper selected by test id  required-fields-obligatory-enabled
  Click label by test id  required-fields-obligatory-enabled-label
  Positive indicator should be visible
  Checkbox wrapper not selected by test id  required-fields-obligatory-enabled
  Logout

Sonja logs in and adds new attachment template
  Sonja logs in
  Open application  ${appname}  ${propertyId}
  Open tab  attachments
  Add empty attachment template  Muu liite  muut  muu
  Logout

Mikko logs back in and browses to the Attachments tab
  Mikko logs in
  Open application  ${appname}  ${propertyId}
  Open tab  attachments

Mikko selects not needed for valtakirja attachment
  Click not needed  hakija.valtakirja

Mikko follows missing attachment link
  Open tab  requiredFieldSummary
  Scroll and click test id  missing-muut-muu

Mikko adds pdf attachment to the template requested by Sonja
  Add attachment version  ${PDF_TESTFILE_PATH}
  Positive indicator should not be visible
  Scroll and click test id  back-to-application-from-attachment
  Wait Until  Tab should be visible  attachments

Mikko fills up a field marked with a VRK warning
  Open tab  info
  Open accordions  info
  Select From List By Value  xpath=//div[@id='application-info-tab']//section[@data-doc-type='uusiRakennus']//select[@data-test-id='kaytto.kayttotarkoitus']  131 asuntolat yms
  Select From List By Value  xpath=//div[@id='application-info-tab']//section[@data-doc-type='uusiRakennus']//select[@data-test-id='lammitys.lammitystapa']  ilmakeskus
  Select From List By Value  xpath=//div[@id='application-info-tab']//section[@data-doc-type='uusiRakennus']//select[@data-test-id='lammitys.lammonlahde']  kaasu

Mikko removes last name for the hakija party in the parties tab
  Open tab  parties
  Open accordions  parties
  ${hakija-etunimi-path} =  Set Variable  //div[@id='application-parties-tab']//section[@data-doc-type='hakija-r']//input[@data-docgen-path='henkilo.henkilotiedot.etunimi']
  ${hakija-sukunimi-path} =  Set Variable  //div[@id='application-parties-tab']//section[@data-doc-type='hakija-r']//input[@data-docgen-path='henkilo.henkilotiedot.sukunimi']
  Wait until  Element should be visible  xpath=${hakija-etunimi-path}
  Scroll to test id  application-invite-hakija-r
  # Applicant is filled by default
  Wait Until  Textfield value should be  xpath=${hakija-etunimi-path}  Mikko
  Wait Until  Textfield value should be  xpath=${hakija-sukunimi-path}  Intonen
  # ok, lets remove lastname to get validation error
  Edit party name  hakija-r  Mikko  ${EMPTY}  henkilo.henkilotiedot
  Focus  xpath=//div[@id='application-parties-tab']//section[@data-doc-type='hakija-r']//input[@data-docgen-path='henkilo.henkilotiedot.sukunimi']
  Wait until  Element should be visible  xpath=//span[contains(@class,'form-input-saved')]
  Wait Until  Textfield value should be  xpath=${hakija-etunimi-path}  Mikko
  Wait Until  Textfield value should be  xpath=${hakija-sukunimi-path}  ${EMPTY}

The filled-up warning field and party info plus the added attachment cause corresponding items to disappear from the "missing required" list in the requiredFieldSummary tab
  Open tab  requiredFieldSummary
  Wait for jQuery
  Wait Until  Element should be visible  xpath=//*[@data-test-id='application-submit-btn']
  # The reduction includes filled fields and the no longer obligatory requirement.
  ${missingRequiredCountAfter} =  Evaluate  ${missingRequiredCount} - 11
  Wait Until  Xpath Should Match X Times  //*[contains(@class,'info-line')]  ${missingRequiredCountAfter}
  Xpath Should Match X Times  //div[@id='application-requiredFieldSummary-tab']//div[@data-test-id='test-application-warnings']//*[contains(@class,'info-line')]  4

Mikko could submit application after missing stuff have been added
  Wait Until  Submit button is enabled
  Logout

Sonja could submit Mikko's application when it's submittable by Mikko
  Sonja logs in
  Open test application in required field summary tab
  Wait Until  Submit button is enabled
  Logout

Submit date is not be visible
  Mikko logs in
  Open test application in required field summary tab
  Element should not be visible  xpath=//span[@data-test-id='application-submitted-date']

Mikko submits application
  Submit application

Mikko cant re-submit application
  Wait Until  Element should not be visible  xpath=//*[@data-test-id='application-submit-btn']

Submit date should be visible
  Wait until  Element should be visible  xpath=//span[@data-test-id='application-submitted-date']

