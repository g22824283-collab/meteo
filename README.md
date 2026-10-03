# Метеорология (Fabric, Minecraft 1.20.1)

Сборка: Java 17, затем `gradle build` (или `./gradlew build`, если скопировать
gradle wrapper из https://github.com/FabricMC/fabric-example-mod, ветка 1.20).
Готовый jar: build/libs/meteo-1.0.0.jar -> в папку mods (нужен Fabric API 0.92.2+1.20.1).

Игра:
- Радар: крафт (железо, громоотвод, блок редстоуна), ПКМ по блоку.
  Кнопка внизу справа переключает отражаемость / доплеровскую скорость.
- /storm spawn single_cell|multicell|supercell|squall_line
- /storm tornado [0-5]  — суперячейка с торнадо нужной силы EF
- /storm clear
- Зрелые суперячейки сами порождают торнадо. Разрушение блоков выключается
  правилом `/gamerule mobGriefing false`.
