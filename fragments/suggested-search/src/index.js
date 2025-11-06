// Get all elements with the class 'badge-item'
const badgeItems = document.querySelectorAll('.badge-item');


// Check if any badge items were found
if (!document.body.classList.contains('has-edit-mode-menu') && badgeItems.length > 0) {
    // Iterate over each badge item and attach a click event listener
    badgeItems.forEach(item => {
        item.addEventListener('click', () => {
            // Get the text content of the clicked badge item
            const query = item.textContent.trim();

            // Construct the redirect URL
            const layoutRelativeURL = Liferay.ThemeDisplay.getLayoutRelativeURL();
			var parts = layoutRelativeURL.split('/');
			var siteFriendlyURL = '';
    		if (parts.length >= 3) {
                siteFriendlyURL = '/web/' + parts[2];
            }
            //const redirectURL = `${siteFriendlyURL}/search?q=${encodeURIComponent(query)}`;
            const redirectURL = `${siteFriendlyURL}/${configuration.searchPage}?q=${encodeURIComponent(query)}`;

            // Trigger the redirect
            window.location.href = redirectURL;
        });
    });
} else {
    console.warn("No elements with class 'badge-item' found.");
}