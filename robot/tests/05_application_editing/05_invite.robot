*** Settings ***

Documentation   Application invites
Suite Teardown  Logout
Resource        ../../common_resource.robot
Resource        ../25_company/company_resource.robot

*** Test Cases ***

Mikko creates a new application
  Mikko logs in
  ${secs} =  Get Time  epoch
  Set Suite Variable  ${appname}  invite${secs}
  Set Suite Variable  ${propertyId}  753-416-5-5
  Create application with state  ${appname}  ${propertyId}  kerrostalo-rivitalo  open

Mikko can see the general invite button and opens invite dialog with it
  Open tab  parties
  Open accordions  parties
  Fill application person invite bubble  teppo@example.com  Tervetuloa muokkaamaan hakemusta
  Scroll and click test id  person-invite-bubble-dialog-cancel

Mikko can see invite paasuunnittelija button
  Element should be visible  xpath=//*[@data-test-id='application-invite-paasuunnittelija']

Mikko invites Teppo
  Invite Teppo

Mikko can't reinvite Teppo
  Open accordions  parties
  Scroll and click test id  application-invite-paasuunnittelija
  Wait until  Element should be visible  invite-email
  Sleep  1
  Input Text  invite-email  teppo@example.com
  Input Text  invite-text  Tervetuloa muokkaamaan taas hakemusta
  Click by test id  application-invite-submit
  Error message is present on invite form
  Invite count is  1
  [Teardown]  logout

Teppo declines invitation
  Teppo logs in
  Wait until  Element should be visible  xpath=//*[@data-test-id='decline-invite-button']
  Element Should Contain  xpath=//div[contains(@class, 'invitation')][1]//h3  ${appname}, Sipoo,
  Element Text Should Be  xpath=//div[contains(@class, 'invitation')][1]//p[@data-test-id='invitation-text-0']  Tervetuloa muokkaamaan hakemusta
  Click by test id  decline-invite-button
  Confirm  dynamic-yes-no-confirm-dialog
  Wait until  Element should not be visible  xpath=//*[@data-test-id='decline-invite-button']
  [Teardown]  logout

Mikko reinvites Teppo
  Mikko logs in
  Open application  ${appname}  ${propertyId}
  Open tab  parties
  Open accordions  parties
  Element should be visible  xpath=//*[@data-test-id='application-invite-paasuunnittelija']
  Invite Teppo
  [Teardown]  logout

Teppo can view application
  Teppo logs in
  Wait Until  Element should be visible  xpath=//div[contains(@class, 'invitation')]//a[@data-test-id='open-application-button']
  Click element  xpath=//div[contains(@class, 'invitation')]//a[@data-test-id='open-application-button']
  Deny yes no dialog
  Application address should be  ${appname}
  Go to page  applications

Teppo accepts invitation
  Wait until  Element should be visible  xpath=//*[@data-test-id='accept-invite-button']
  Element Should Contain  xpath=//div[contains(@class, 'invitation')][1]//h3  ${appname}, Sipoo,
  Element Text Should Be  xpath=//div[contains(@class, 'invitation')]//p[@data-test-id='invitation-text-0']  Tervetuloa muokkaamaan hakemusta
  Click by test id  accept-invite-button
  Wait until  Element should not be visible  xpath=//*[@data-test-id='accept-invite-button']

Teppo can edit Mikko's application
  Open application  ${appname}  ${propertyId}
  Open accordions  info
  # OnChange event does not seem to get triggered. Do it manually.
  Wait until  Scroll and click test id  maaraala-checkbox
  Input text with jQuery  input[data-test-id="kiinteisto.maaraalaTunnus"]  1024
  Textfield Value Should Be  xpath=//input[@data-test-id='kiinteisto.maaraalaTunnus']  1024
  Wait Until  Element should be visible  //*[@data-test-id='save-indicator']
  [Teardown]  logout

Mikko comes back and can see Teppos modification
  Mikko logs in
  Open application  ${appname}  ${propertyId}
  Wait Until  Textfield Value Should Be  xpath=//input[contains(@data-test-id,'kiinteisto.maaraalaTunnus')]  1024

Mikko can see that Teppo has accepted invitation
  Open tab  parties
  Open accordions  parties
  # Check that invite accepted timestamp span is present
  Element should be visible  xpath=//*[@data-test-id='invite-accepted-span']

Only one unsubscribe button
  Xpath Should Match X Times  //div[@id='application-parties-tab']//button[@data-test-id='unsubscribeNotifications']  1

Mikko unsubscribes notifications
  Wait Until  Element should be visible  xpath=//div[@id='application-parties-tab']//button[@data-test-id='unsubscribeNotifications']
  Click by test id  unsubscribeNotifications
  Wait Until  Element should not be visible  xpath=//div[@id='application-parties-tab']//button[@data-test-id='unsubscribeNotifications']
  Wait Until  Element should be visible  xpath=//div[@id='application-parties-tab']//button[@data-test-id='subscribeNotifications']

Mikko subscribes notifications
  Click by test id  subscribeNotifications
  Wait Until  Element should not be visible  xpath=//div[@id='application-parties-tab']//button[@data-test-id='subscribeNotifications']
  Wait Until  Element should be visible  xpath=//div[@id='application-parties-tab']//button[@data-test-id='unsubscribeNotifications']

Mikko can see invite paasuunnittelija button again
  Element should be visible  xpath=//*[@data-test-id='application-invite-paasuunnittelija']

Mikko can't invite himself
  Scroll and click test id  application-invite-paasuunnittelija
  Input Text  invite-email  mikko@example.com
  Input Text  invite-text  Voinko kutsua itseni?
  Click by test id  application-invite-submit
  Error message is present on invite form
  Invite count is  0

Mikko invites Solita
  Invite company to application  Solita Oy

Mikko decides to go to the desert, put on his ipod, and listen some some British hard-rock band
  Logout

Solita accepts invite
  User logs in  kaino@solita.fi  kaino123  Kaino Solita
  Wait until  Element should be visible  xpath=//*[@data-test-id='accept-invite-button']
  Element Should Contain  xpath=//div[@class='invitation'][1]//h3  Yritysvaltuutus: ${appname}, Sipoo,
  Click by test id  accept-invite-button
  Wait until  Element should not be visible  xpath=//*[@data-test-id='accept-invite-button']

Kaino Solita opens the application
  Open application  ${appname}  ${propertyId}
  Open tab  parties

Kaino can unsubscribe notifications
  Click visible test id  unsubscribeNotifications

Kaino can now subscribe notifications
  Click visible test id  subscribeNotifications

Let's add Pena to Solita
  Open company user listing
  Invite existing user  pena@example.com  Pena  Panaani
  Accept invitation  pena@example.com
  [Teardown]  logout

Pena can open application but cannot manage subscriptions
  Pena logs in
  Open application  ${appname}  ${propertyId}
  Open tab  parties
  Element should be visible  jquery=div.authorized-parties
  No such test id  unsubscribeNotifications
  No such test id  subscribeNotifications
  [Teardown]  logout

Sonja (the Authority) is not allowed to invite people
  Sonja logs in
  Open application  ${appname}  ${propertyId}
  Open tab  parties
  Element should not be visible  xpath=//*[@data-test-id='application-add-invite']
  [Teardown]  logout

Mikko invites previously unknown user Oskari as paasuunnittelija
  Mikko logs in
  Open application  ${appname}  ${propertyId}
  Open tab  parties
  Open accordions  parties
  Element should be visible  xpath=//*[@data-test-id='application-invite-paasuunnittelija']
  Scroll and click test id  application-invite-paasuunnittelija
  Wait until  Element should be visible  invite-email
  Input Text  invite-email  oskari@example.com
  Input Text  invite-text  Tuu mukaan tunkkaan lupaa
  Click by test id  application-invite-submit
  Wait until  Mask is invisible
  Wait until  Element should not be visible  invite-email
  Wait until  Invite count is  1

# TODO: should create new user "oskari@example.com" and make sure he has access

# There are now three parties: Mikko, Teppo, Solita and Oskari (not accepted yet).
Solita is not included in the hakija person select
  Scroll and click  [data-doc-type='hakija-r'] [data-docgen-path='_selected'][value=henkilo]
  # Empty, Mikko and Teppo
  Person selector count is  hakija-r  henkilo.userId  3
  Person selector includes  hakija-r  henkilo.userId  Mikko
  Person selector includes  hakija-r  henkilo.userId  Teppo
  Person selector includes  hakija-r  henkilo.userId  Kaino  0

Solita is included in the paasuunnittelija person select
  # Empty, Mikko, Teppo, Kaino and Pena
  Person selector count is  paasuunnittelija  userId  5
  Person selector includes  paasuunnittelija  userId  Mikko
  Person selector includes  paasuunnittelija  userId  Teppo
  Person selector includes  paasuunnittelija  userId  Solita Kaino, Solita Oy
  Person selector includes  paasuunnittelija  userId  Panaani Pena, Solita Oy

# Tyonjohtaja on poistettu tavallisilta R-hakemuksilta (LUPA-1603).
# Testataan tyonjohtajan kutsuminen erikseen omalla hakemuksellaan.
Mikko creates a new tyonjohtaja application
  Create application the fast way  ${appname}-tj  ${propertyId}  tyonjohtajan-nimeaminen

Mikko invites previously unknown user Unto as tyonjohtaja
  Open tab  parties
  Open accordions  parties
  Wait until  Element should be visible  xpath=//div[@id="application-parties-tab"]//*[@data-test-id='application-invite-tyonjohtaja']
  Scroll and click test id  application-invite-tyonjohtaja
  Wait until  Element should be visible  invite-email
  Input Text  invite-email  unto@example.com
  Input Text  invite-text  Tuu mulle tyonjohtajaksi
  Click by test id  application-invite-submit
  Wait until  Mask is invisible
  Wait until  Element should not be visible  invite-email
  Wait until  Invite count is  1

Unto hasn't accepted auth, so it can't be upgreded
  Element should not be visible by test id  change-auth-unto@example.com

Mikko deletes authorization from Unto
  Click by test id  remove-auth-unto@example.com
  Confirm yes no dialog
  Wait until  Invite count is  0
  Logout

Frontend errors check
  There are no frontend errors

*** Keywords ***

Error message is present on invite form
  Wait until  Element should be visible  xpath=//div[@id='dialog-valtuutus']//div[contains(@class, 'context-error')]
  Click Element  xpath=//div[@id='dialog-valtuutus']//p[contains(@class,'close')]
  Wait until  Mask is invisible

Mask is invisible
  Element should not be visible  xpath=//div[@id='ModalDialogMask']

Invite Teppo
  Invite count is  0
  Scroll and click test id  application-invite-paasuunnittelija
  Wait until  Element should be visible  invite-email
  Input Text  invite-text  Tervetuloa muokkaamaan hakemusta
  Element should be disabled  xpath=//*[@data-test-id='application-invite-submit']
  Input Text  invite-email  teppo@example
  Element should be disabled  xpath=//*[@data-test-id='application-invite-submit']
  Input Text  invite-email  teppo@example.com
  Element should be enabled  xpath=//*[@data-test-id='application-invite-submit']
  Click by test id  application-invite-submit
  Wait until  Mask is invisible
  Wait until  Element should not be visible  invite-email
  Wait until  Invite count is  1

Person selector includes
  [Arguments]  ${doc-type}  ${user-id}  ${text}  ${count}=1
  jQuery should match X times  [data-doc-type='${doc-type}'] select[data-test-id='${user-id}'] option:contains(${text})  ${count}

Person selector count is
  [Arguments]  ${doc-type}  ${user-id}  ${count}
  jQuery should match X times  [data-doc-type='${doc-type}'] select[data-test-id='${user-id}'] option  ${count}
  Person selector includes  ${doc-type}  ${user-id}  Valitse
