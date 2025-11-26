@echo off
echo Generating RGB LED Block textures...
echo.

python generate_led_textures.py

if %ERRORLEVEL% EQU 0 (
    echo.
    echo Done! Textures generated successfully.
    echo You may need to rebuild the project to see the changes.
) else (
    echo.
    echo Error: Failed to generate textures.
    echo Make sure Python and PIL/Pillow are installed.
    echo Install with: pip install pillow
)

pause

