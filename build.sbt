scalaVersion := "3.9.0"

lazy val root = project
  .in(file("."))
  .settings(
    name := "Raspred_Sys",
    version := "0.1.0",
    // Запускаем программы в отдельном процессе и разрешаем ввод с клавиатуры (нужно для клиента)
    run / fork := true,
    run / connectInput := true
  )
