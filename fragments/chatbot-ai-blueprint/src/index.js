import * as OAuth2 from '@liferay/oauth2-provider-web/client';

// A function to inject a new message into the chat history
function appendMessage(sender, text) {
  const messagesDiv = document.querySelector('.chat-messages');
  if (messagesDiv) {
    const messageElement = document.createElement('div');
    messageElement.classList.add('message', `${sender}-message`);
    
    if (sender === 'bot') {
      // For the bot, stream the message to simulate typing
      streamBotMessage(messageElement, text);
    } else {
      // For the user, display the message immediately
      messageElement.textContent = text;
    }

    messagesDiv.appendChild(messageElement);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
  }
}

// Streams the bot's message with a typing effect
function streamBotMessage(element, htmlString) {  
  // First, decode the HTML entities from the server response
  const decodedString = decodeHtmlEntities(htmlString);
  const messagesDiv = document.querySelector('.chat-messages');
  
  // Set the entire HTML at once to ensure it's parsed correctly.
  element.innerHTML = decodedString;
  messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

// Decodes HTML entities (e.g., &lt; to <)
function decodeHtmlEntities(html) {
  const txt = document.createElement('textarea');
  txt.innerHTML = html;
  return txt.value;
}

// Shows the "bot is typing" animation
function showBotTyping() {
  const messagesDiv = document.querySelector('.chat-messages');
  if (messagesDiv) {
    const typingElement = document.createElement('div');
    typingElement.classList.add('message', 'bot-message', 'typing-indicator');
    typingElement.innerHTML = '<span></span><span></span><span></span>';
    messagesDiv.appendChild(typingElement);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
  }
}

// Removes the "bot is typing" animation
function hideBotTyping() {
  const typingIndicator = document.querySelector('.typing-indicator');
  if (typingIndicator) {
    typingIndicator.remove();
  }
}

/* --- Main Setup Logic --- */

// We'll use a simple "marker" to make sure this code only runs ONCE.
if (!window.chatbotListenersAttached) {

  // --- Configuration from neilCMSChat ---
  const oAuth2Client = await OAuth2.FromUserAgentApplication(configuration.userAgentERC);
  const blueprintExternalReferenceCode = configuration.blueprintExternalReferenceCode;
  const searchScope = configuration.searchScope;

  // This function will be called when the form is submitted.
  async function handleFormSubmit(event) {
    event.preventDefault();
    
    const form = event.target;
    const input = form.querySelector('.chat-input');
    if (!input) return;
    
    const userMessage = input.value.trim();
    if (userMessage === '') return;

    // Display user's message immediately
    appendMessage('user', userMessage);
    
    // Clear input and disable it
    input.value = '';
    input.disabled = true;

    // Show typing indicator
    showBotTyping();

    const chatWindow = document.querySelector('.chat-messages');
    const url = `${configuration.protocol}://${configuration.serviceHostname}:${configuration.servicePort}/cmschat/completions`;

    try {
      const fetchOptions = {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          messages: Array.from(chatWindow.querySelectorAll('.message:not(.typing-indicator)'))
            .map(child => child.textContent),
          blueprintExternalReferenceCode: blueprintExternalReferenceCode,
          scope: searchScope,
          roles: Array.from(
            chatWindow.querySelectorAll('.message:not(.typing-indicator)')
          ).map(child =>
            child.classList.contains('bot-message') ? 'assistant' : 'user'
          ),
        }),
      };

      let response;

      if (Liferay.ThemeDisplay.isSignedIn()) {
        console.log("User is signed in, using OAuth2 fetch");
        response = await oAuth2Client?.fetch(url, fetchOptions);
      } else {
        console.log("User is guest, using native fetch");
        response = await fetch(url, fetchOptions);
      }

      hideBotTyping();

      if (response.ok) {
        const data = await response.json();
        console.log("Response from server:", data);
        if (data.assistant) {
          appendMessage('bot', data.assistant);
        }
      } else {
        console.error("Network response was not ok", response);
        appendMessage('bot', "I'm sorry, there was an error. Please, try again.");
      }
    } catch (error) {
      hideBotTyping();
      console.error("There was a problem with the fetch operation:", error);
      return "I'm sorry, I am having connection issues.";
    } finally {
      // Re-enable input
      input.disabled = false;
      input.focus();
    }
  }

  let welcomeMessageShown = false;

  document.body.addEventListener('click', function(event) {
    if (event.target.closest('.chat-launcher')) {
      document.body.classList.add('chat-is-open');

      // Add the welcome message only on the first open
      const messagesDiv = document.querySelector('.chat-messages');
      if (!welcomeMessageShown && messagesDiv && messagesDiv.childElementCount === 0) {
        const userName = Liferay.ThemeDisplay.getUserName().split(' ')[0] || '';
        const welcomeMessage = `Hello ${userName}. How can I help you today?`;
        //const welcomeMessage = `Hola ${userName} ¿Cómo puedo ayudarte hoy?`;
        appendMessage('bot', welcomeMessage);
        welcomeMessageShown = true;
      }
    }
    if (event.target.closest('.chat-close-button')) {
      document.body.classList.remove('chat-is-open');
    }
  });

  document.body.addEventListener('submit', function(event) {
    if (event.target.classList.contains('chat-input-form')) {
      handleFormSubmit(event);
    }
  });

  // Set the marker so this code doesn't run again
  window.chatbotListenersAttached = true;
}