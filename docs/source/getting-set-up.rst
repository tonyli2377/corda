Getting set up
==============

Install the Oracle JDK 8u45 or higher. OpenJDK will probably also work but I'm not testing with that.

Then install IntelliJ version 15 community edition:

   https://www.jetbrains.com/idea/download/

Upgrade the Kotlin plugin to the latest version (1.0-beta-2423) by clicking "Configure > Plugins" in the opening screen,
then clicking "Install JetBrains plugin", then searching for Kotlin, then hitting "Upgrade" and then "Restart".

Choose "Check out from version control" and use this git URL

     https://your_username@bitbucket.org/R3-CEV/playground.git

Agree to the defaults for importing a Gradle project. Wait for it to think and download the dependencies.

Right click on the tests directory, click "Run -> All Tests" (note: NOT the first item in the submenu that has the
gradle logo next to it).

The code should build, the unit tests should show as all green.

You can catch up with the latest code by selecting "VCS -> Update Project" in the menu.

Doing it without IntelliJ
-------------------------

If you don't want to explore or modify the code in a local IDE, you can also just use the command line and a text editor:

* Run ``./gradlew test`` to run the unit tests.
* Run ``git pull`` to upgrade