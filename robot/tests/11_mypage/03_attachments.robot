*** Settings ***

Documentation   User's own attachments
Suite teardown  Logout
Resource       ../../common_resource.robot
Variables      ../06_attachments/variables.py

*** Test Cases ***

Mikko uploads CV
  Mikko logs in
  Click Element  user-name
  Wait for Page to Load  Mikko  Intonen
  Click enabled by test id  test-add-architect-attachment
  Select From List  attachmentType  osapuolet.cv
  Choose File      xpath=//input[@type='file']  ${TXT_TESTFILE_PATH}
  Click enabled by test id  userinfo-upload-ok
  Wait Until Page Contains  ${TXT_TESTFILE_NAME}

Mikko copies his attachments to application
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  Omat-liitteet-${secs}
  Create application the fast way  ${appname}  753  753-416-25-30  kerrostalo-rivitalo
  Open tab  attachments
  Select attachment operation option from dropdown  attachmentsCopyOwn
  Confirm  dynamic-yes-no-confirm-dialog
  Wait Until Page Contains  ${TXT_TESTFILE_NAME}

Copy own attachments button is not shown to non-architect
  Click Element  user-name
  Wait for Page to Load  Mikko  Intonen
  Click Element  architect
  Save User Data
  Go Back
  Reload Page
  Wait until  Page should contain element  xpath=//select[@data-test-id="attachment-operations-select-lower"]//option[@value='attachmentsCopyOwn']

#Name should have changed in Swedish page too
#  Click link  xpath=//*[@data-test-id='lang-sv']
#  Wait for Page to Load  Mika  Intola
#  User should be logged in  Mika Intola


*** Keywords ***

Save User Data
  Click enabled by test id  save-my-userinfo
  Wait Until  Element Should be visible  //*[@data-test-id='save-my-userinfo-ok']

Wait for Page to Load
  [Arguments]  ${firstName}  ${lastName}
  Wait Until  Element Should be visible  //*[@data-test-id='save-my-userinfo']
  Wait Until  Textfield Value Should Be  firstName  ${firstName}
  Wait Until  Textfield Value Should Be  lastName   ${lastName}
