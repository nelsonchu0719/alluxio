#!/bin/sh

# install client dependencies, added by Chi-fan Chu
# All these dependencies will be packed to alluxio-dev.box and save future boot time.
cd /AlluxioBasicIOTest
mvn compile
