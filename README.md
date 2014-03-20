# Docker regsitry build plugin for Jenkins

[![Build Status](https://buildhive.cloudbees.com/job/cloudbees/job/jenkins-docker-registry-builder/badge/icon)](https://buildhive.cloudbees.com/job/cloudbees/job/jenkins-docker-registry-builder/)

If you want to build and push your Docker based project to the docker registry (including private repos), then you are in luck!

Features:
   * Only a Dockerfile needed to build your project
   * Publish to docker index/registry
   * nocache option (for rebuild of all Dockerfile steps)
   * publish option
   * manage registry credentials for private and public repos
   * tag the image built - use any Jenkins env. variables.


 ## Dockerfile as build config

 A Dockerfile is a convenient way to express build instructions.

 As the Beatles sand, all you need is Dockerfile, and love. If you have a Dockerfile in the root
 of your project, then no further configuration is needed.

 ## Usage

 Set your credentials (username, email, password) in Manage Jenkins, then in any build add a build step using docker.
 The usual docker build caching mechanism applies - and you can choose to publish, or not, the resultant image.

(insert image here - with sample tag with BUILD_NUMBER)

(insert config screen)

### Why use a Dockerfile

Defining your build as a Dockerfile means that it will run in a consistent linux environment, no matter where the build is run.
You also end up with an image (in a repository, possibly pushed to a registry) that can then be deployed - the exact same image you built.

Dockerfiles can also help speed up builds. If there has been no change relative to a build instruction in the Dockerfile - then a cached version of that portion of the build can be used (this is a fundamental feature of docker)


### Terminology

Docker has some confusing terminology - quick refresher:

 * Repository - collection of docker images and tags. You "push" a repository to a registry, and when you build an image, it gets added to a repository.
 By default this is the "latest" version (tag)
 * Registry - a place you push docker images/repos to
 * Push - deploy a docker repo (presumably with a new image) to a remote registry
 * Image - a docker image is what you build from a Dockerfile - it gets added to a repo
