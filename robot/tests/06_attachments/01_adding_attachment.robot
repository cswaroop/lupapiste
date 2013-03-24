*** Settings ***

Documentation  Mikko adds an attachment
Suite teardown  Logout
Resource       ../../common_resource.robot
Variables      variables.py

*** Test Cases ***

Mikko goes to empty attachments tab
  [Tags]  attachments
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  attachments${secs}
  Mikko logs in
  Create application the fast way  ${appname}  753  75341600250030
  Open tab  attachments

Mikko adds txt attachment without comment
  [Tags]  attachments
  Add attachment  ${TXT_TESTFILE_PATH}  ${EMPTY}
  Application state should be  draft

Mikko opens attachment details
  [Tags]  attachments
  Open attachment details

Mikko does not see Reject-button
  [Tags]  attachments
  Element should not be visible  test-attachment-reject

Mikko does not see Approve-button
  [Tags]  attachments
  Element should not be visible  test-attachment-approve

Mikko deletes attachment
  [Tags]  attachments
  Click enabled by test id  delete-attachment
  Confirm  dialog-confirm-delete-attachment
  Wait Until Page Contains  75341600250030
  Wait Until  Page Should Not Contain  xpath=//a[@data-test-type="muut.muu"]

Mikko adds txt attachment with comment
  [Tags]  attachments
  Add attachment  ${TXT_TESTFILE_PATH}  ${TXT_TESTFILE_DESCRIPTION}
  Wait Until  Application state should be  open

Mikko see that attachment is for authority
  [Tags]  attachments
  Wait Until  Attachment state should be  requires_authority_action

Mikko adds comment
  [Tags]  attachments
  Open attachment details
  Input text  attachment.commentText  mahtava liite!
  Click button  attachment.addComment

Comment is added
  [Tags]  attachments
  Wait Until  Comment count is  2

Switch user
  [Tags]  attachments
  Logout
  Sonja logs in

Sonja goes to attachment tab
  [Tags]  attachments
  Open application  ${appname}  75341600250030
  Open tab  attachments

Sonja see that attachment is for authority
  [Tags]  attachments
  Wait Until  Attachment state should be  requires_authority_action

Sonja opens attachment details
  [Tags]  attachments
  Open attachment details

Sonja sees Reject-button which is enabled
  [Tags]  attachments
  Wait Until  Element should be visible  test-attachment-reject
  Element should be enabled  test-attachment-reject

Sonja sees Approve-button which is enabled
  [Tags]  attachments
  Wait until  Element should be visible  test-attachment-approve
  Element should be enabled  test-attachment-approve

Sonja rejects attachment
  [Tags]  attachments
  Element should be enabled  test-attachment-reject
  Click element  test-attachment-reject

Reject-button should be disabled
  [Tags]  attachments
  Wait until  Element should be disabled  test-attachment-reject

Sonja approves attachment
  [Tags]  attachments
  Wait until  Element should be enabled  test-attachment-approve
  Click element  test-attachment-approve

Approve-button should be disabled
  [Tags]  attachments
  Wait until  Element should be disabled  test-attachment-approve

*** Keywords ***

Add attachment
  [Arguments]  ${path}  ${description}
  Wait and click   xpath=//button[@data-test-id="add-attachment"]
  Select Frame     uploadFrame
  Wait until       Element should be visible  test-save-new-attachment
  Wait until       Page should contain element  xpath=//form[@id='attachmentUploadForm']//option[@value='muut.muu']
  Select From List  attachmentType  muut.muu
  Input text       text  ${description}
  Choose File      xpath=//form[@id='attachmentUploadForm']/input[@type='file']  ${path}
  Click element    test-save-new-attachment
  Unselect Frame
  Wait Until Page Contains  Muu liite

Open attachment details
  Wait Until  Page Should Contain Element  xpath=//a[@data-test-type="muut.muu"]
  # Make sure the element is visible on browser view before clicking. Take header heigth into account.
  #Execute Javascript  window.scrollTo(0, $("[data-test-type='muut.muu']").position().top - 130);
  Focus  xpath=//a[@data-test-type="muut.muu"]
  Click element  xpath=//a[@data-test-type="muut.muu"]
  Wait Until  Element Should Be Visible  test-attachment-file-name
  Wait Until Page Contains  ${TXT_TESTFILE_NAME}
  Element Text Should Be  test-attachment-file-name  ${TXT_TESTFILE_NAME}
  Element Text Should Be  test-attachment-version  1.0

Attachment state should be
  [Arguments]  ${state}
  ## Fragile: assumes there is only one element that has data-test-state
  ${STATE_ATTR_VALUE} =  Get Element Attribute  xpath=//*[@data-test-state and @data-test-type="muut.muu"]@data-test-state
  Log  ${STATE_ATTR_VALUE}
  Should Be Equal  ${STATE_ATTR_VALUE}  ${state}

Comment count is
  [Arguments]  ${amount}
  Xpath Should Match X Times  //section[@id='attachment']//tr[contains(@class, 'comment')]  ${amount}
