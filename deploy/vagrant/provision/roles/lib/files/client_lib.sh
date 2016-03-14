#!/bin/sh

# install client dependencies, added by Nelson
# All these dependencies will be packed to alluxio-dev.box and save future boot time.
cd /AlluxioBasicIOTest
mvn compile
