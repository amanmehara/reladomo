#!/bin/ksh

#  Copyright 2016 Goldman Sachs.
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.

#
# Get hold of the directory we reside in.
#
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '.*/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done
CURRENTDIR=`dirname "$PRG"`

. $CURRENTDIR/setenv.ksh

chmod 755 $CURRENTDIR/boot.ksh
$CURRENTDIR/boot.ksh

chmod 755 $CURRENTDIR/antbuild.ksh
$CURRENTDIR/antbuild.ksh $CURRENTDIR/build.xml $*
