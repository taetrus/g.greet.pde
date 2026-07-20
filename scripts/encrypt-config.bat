@echo off
rem Encrypts a config value for configs\<bundle>\conf\*.properties.
rem All logic lives in scripts\EncryptConfig.java (single-file source-launch).
rem   encrypt-config.bat <plaintext>            print ENC(...) to paste into a .properties file
rem   encrypt-config.bat --decrypt "ENC(...)"   round-trip check: print the plaintext
cd /d "%~dp0.."
java scripts\EncryptConfig.java %*
exit /b %errorlevel%
