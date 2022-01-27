# reSync

These scripts will reload reMarkable2 customizations after a software update, which will revert any customizations.

## Usage

1. Place any custom images (suspend screen, for example) in `images/`.
2. Place any custom templates in `templates/`.
3. Edit `custom_templates.json`, adding any custom templates you have placed in `templates\`, along with their attributes.
4. Edit `excludes.txt`, adding any templates you do not wish to see as choices on the reMarkable2 tablet. The name listed in this file is the filename, not the display name of the template.
5. Run `./run` with the appropriate argument
    - **sync** - performs a full sync of the templates and images
    - **test** - simply connects and disconnects, verifying connectivity
    - **backup** - performs backup of notebook data
