# GCP Domains to Pkl

Makes it a bit easier to migrate from GCP Domains to IaC domains with Pkl

Usage:
1. Make sure you've setup the usual GCP CLI / SDK Auth stuff
1. `export PROJECT_ID=your_project_id`
1. Run: `./gradlew run`

This does the following:
1. Gets a list of all the GCP Domains in your project
1. Gets details about forwarding and all DNS records
1. Unlocks each domain
1. Gets a transfer auth code for each domain
1. Outputs the domain details in Pkl so they can be IaC'd with the [cfn-pkl-extras](https://github.com/jamesward/cfn-pkl-extras) library
