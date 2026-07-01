# Сборка SimReset

## 1. Подготовь lib-зависимости

Скопируй из папки `mods/` сервера следующие jar-файлы в папку `libs/` этого проекта:

- `sable-neoforge-1.21.1-2.0.3.jar`   (или как называется jar sable)
- `aeronautics-neoforge-1.21.1-X.X.jar` (содержит и simulated внутри)

Если simulated идёт отдельным jar — скопируй оба. `build.gradle` подхватит всё, что в `libs/*.jar`.

## 2. Сборка

```bash
./gradlew build
```

Готовый jar появится в `build/libs/simreset-1.0.0.jar`.

## 3. Установка

Положи `simreset-1.0.0.jar` в папку `mods/` сервера.

## 4. Использование команд (пермишен уровень 2)

```
# Дизассемблировать все sub-level в текущем измерении
/sable disassemble all

# Дизассемблировать по UUID
/sable disassemble uuid 550e8400-e29b-41d4-a716-446655440000

# Дизассемблировать по имени (display name из /sable storage)
/sable disassemble name "МойКорабль"

# Дизассемблировать всё и сразу собрать заново (= перегенерация)
/sable reassemble all

# То же, но точечно
/sable reassemble uuid 550e8400-e29b-41d4-a716-446655440000
/sable reassemble name "МойКорабль"
```

## Заметки

- `disassemble` — мгновенный, без ожидания выравнивания физики.
- `reassemble` — дизассемблирует, затем через 1 тик собирает обратно через PhysicsAssembler.
- Sub-level без `primary assembler` пропускается с предупреждением в чате.
- UUID можно узнать через `/sable storage find_all_sub_levels` или `/sable info`.
- Имя устанавливается в GUI ассемблера и видно через `/sable storage find <name>`.
