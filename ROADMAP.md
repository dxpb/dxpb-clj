- Read package existence from an S3 endpoint.
- Package build readiness based on dependency existence.
- Generate nomad job to create a package
- Given a list of packages, build them
- Assume all packages are in the list of packages, build them.

Later:
- Flexibly add new build profiles
- Allow disabling of build profiles

Ongoing:
Make sure every action has the ability to be triggered from the web, and from
code just as easily.
