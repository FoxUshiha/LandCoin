@echo off
title Compilador LandCoin v2.0 - Java 17

echo ============================================
echo Compilador do Plugin LandCoin
echo (Integracao com CoinCard - Sistema de Terrenos)
echo ============================================
echo.

echo Procurando Java 17 instalado...
echo.

set JDK_PATH=

rem Procura JDK 17 em locais comuns
for /d %%i in ("C:\Program Files\Java\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Java\jdk17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\AdoptOpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\OpenJDK\jdk-17*") do set JDK_PATH=%%i
for /d %%i in ("C:\Program Files\Amazon Corretto\jdk17*") do set JDK_PATH=%%i

if "%JDK_PATH%"=="" (
    echo ============================================
    echo ERRO: JDK 17 nao encontrado!
    echo Instale o Java 17 JDK e tente novamente.
    echo ============================================
    pause
    exit /b 1
)

echo Java 17 encontrado em: %JDK_PATH%
echo.

set JAVAC="%JDK_PATH%\bin\javac.exe"
set JAR="%JDK_PATH%\bin\jar.exe"

echo ============================================
echo Preparando ambiente de compilacao...
echo ============================================
echo.

echo Limpando pasta out...
if exist out (
    rmdir /s /q out >nul 2>&1
)
mkdir out
mkdir out\com
mkdir out\com\foxsrv
mkdir out\com\foxsrv\landcoin

echo.
echo ============================================
echo Verificando dependencias...
echo ============================================
echo.

REM Verificar Spigot API
if not exist spigot-api-1.20.1-R0.1-SNAPSHOT.jar (
    echo [ERRO] spigot-api-1.20.1-R0.1-SNAPSHOT.jar nao encontrado!
    echo.
    echo Certifique-se de que o arquivo spigot-api-1.20.1-R0.1-SNAPSHOT.jar está na pasta raiz.
    pause
    exit /b 1
) else (
    echo [OK] Spigot API encontrado
)

REM Verificar Vault API (opcional, para melhor resolução de nomes)
if not exist Vault.jar (
    echo [AVISO] Vault.jar nao encontrado na pasta raiz!
    echo O plugin LandCoin pode usar Vault para melhor resolução de nomes de jogadores offline.
    echo Continuando compilacao sem Vault...
    echo.
    set VAULT_PATH=
) else (
    echo [OK] Vault API encontrado
    set VAULT_PATH=Vault.jar
)

REM Verificar CoinCard API
if not exist CoinCard.jar (
    echo [AVISO] CoinCard.jar nao encontrado na pasta raiz!
    echo O plugin LandCoin requer o CoinCard como dependencia.
    echo.
    echo Certifique-se de que o CoinCard.jar esta na pasta plugins do servidor.
    echo Continuando compilacao mesmo assim...
    echo.
    set COINCARD_PATH=
) else (
    echo [OK] CoinCard API encontrado
    set COINCARD_PATH=CoinCard.jar
)

echo.
echo ============================================
echo Compilando LandCoin...
echo ============================================
echo.

REM Montar classpath
set CLASSPATH="spigot-api-1.20.1-R0.1-SNAPSHOT.jar"
if defined COINCARD_PATH (
    set CLASSPATH=%CLASSPATH%;CoinCard.jar
)
if defined VAULT_PATH (
    set CLASSPATH=%CLASSPATH%;Vault.jar
)

REM Compilar com as dependências necessárias
%JAVAC% --release 17 -d out ^
-classpath %CLASSPATH% ^
-sourcepath src ^
src/com/foxsrv/landcoin/LandCoin.java

if %errorlevel% neq 0 (
    echo ============================================
    echo ERRO AO COMPILAR O PLUGIN!
    echo ============================================
    echo.
    echo Verifique os erros acima e corrija o codigo.
    pause
    exit /b 1
)

echo.
echo Compilacao concluida com sucesso!
echo.

echo ============================================
echo Copiando arquivos de recursos...
echo ============================================
echo.

REM Copiar plugin.yml
if exist resources\plugin.yml (
    copy resources\plugin.yml out\ >nul
    echo [OK] plugin.yml copiado
) else (
    echo [AVISO] plugin.yml nao encontrado em resources\
    echo Criando plugin.yml padrao...
    
    (
        echo name: LandCoin
        echo version: 2.0
        echo main: com.foxsrv.landcoin.LandCoin
        echo api-version: 1.20
        echo author: FoxOficial2
        echo description: A complete land claiming and protection plugin with CoinCard integration
        echo depend: [CoinCard]
        echo softdepend: [Vault, PlaceholderAPI]
        echo.
        echo commands:
        echo   land:
        echo     description: Main land command
        echo     usage: /land ^<subcommand^>
        echo     aliases: [lands, l]
        echo   selection:
        echo     description: Selection tool command
        echo     usage: /selection [clear^|info^|view]
        echo     aliases: [sel, wand]
        echo.
        echo permissions:
        echo   landcoin.*:
        echo     description: All LandCoin permissions
        echo     default: op
        echo     children:
        echo       landcoin.user: true
        echo       landcoin.admin: true
        echo   landcoin.user:
        echo     description: Basic user permissions
        echo     default: true
        echo   landcoin.admin:
        echo     description: Admin permissions
        echo     default: op
        echo     children:
        echo       landcoin.admin.reload: true
        echo       landcoin.admin.bypass: true
        echo   landcoin.admin.reload:
        echo     description: Reload the plugin
        echo     default: op
        echo   landcoin.admin.bypass:
        echo     description: Bypass all land protections
        echo     default: op
    ) > out\plugin.yml
    echo [OK] plugin.yml criado automaticamente
)

REM Copiar config.yml
if exist resources\config.yml (
    copy resources\config.yml out\ >nul
    echo [OK] config.yml copiado
) else (
    echo [AVISO] config.yml nao encontrado em resources\
    echo Criando config.yml padrao...
    
    (
        echo # LandCoin Configuration
        echo.
        echo ServerCard: ""
        echo.
        echo LandBuyPrice: 0.00000001
        echo LandSellPrice: 0.00000001
        echo.
        echo LandDailyTax: 0.00000001
        echo.
        echo TaxPercentForRent: 0.01
        echo TaxForSell: 0.01
        echo.
        echo MaxPaymentAttempts: 10
    ) > out\config.yml
    echo [OK] config.yml criado automaticamente
)

echo.
echo ============================================
echo Criando arquivo JAR...
echo ============================================
echo.

cd out

REM Criar JAR com todos os recursos
%JAR% cf LandCoin.jar com plugin.yml config.yml

cd ..

echo.
echo ============================================
echo PLUGIN COMPILADO COM SUCESSO!
echo ============================================
echo.
echo Arquivo gerado: out\LandCoin.jar
echo.
dir out\LandCoin.jar
echo.
echo ============================================
echo IMPORTANTE - REQUISITOS PARA EXECUCAO:
echo ============================================
echo.
echo 1 - O plugin CoinCard DEVE estar instalado no servidor
echo 2 - Certifique-se de que o CoinCard esta configurado corretamente
echo 3 - Adicione no plugin.yml do CoinCard:
echo     ^<permission^>
echo         ^<name^>coincard.api^</name^>
echo     ^</permission^>
echo 4 - Certifique-se de que ambos os plugins estao na pasta plugins/
echo.
echo ============================================
echo Para instalar:
echo ============================================
echo.
echo 1 - Copie out\LandCoin.jar para a pasta plugins do servidor
echo 2 - Copie CoinCard.jar para a pasta plugins do servidor (se ainda nao estiver)
echo 3 - Copie Vault.jar para a pasta plugins do servidor (opcional, para melhor resolução de nomes)
echo 4 - Reinicie o servidor ou use /reload confirm
echo 5 - Configure o ServerCard no config.yml com o card do servidor
echo.
echo ============================================
echo Comandos basicos apos instalacao:
echo ============================================
echo.
echo /selection - Pega a varinha de selecao
echo /land claim - Claima os chunks selecionados
echo /land unclaim - Vende chunks selecionados
echo /land area claim - Cria uma sub-area
echo /land info - Mostra info da land atual
echo /land rent - Aluga a land atual
echo /land trust ^<player^> - Da trust para um jogador
echo.
echo ============================================
echo.

pause