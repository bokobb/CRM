import 'package:flutter_test/flutter_test.dart';

import 'package:crm_mobile/main.dart';

void main() {
  testWidgets('App starts and shows the dashboard app bar', (WidgetTester tester) async {
    await tester.pumpWidget(const CrmApp());
    await tester.pump();

    expect(find.text('Дашборд'), findsWidgets);
  });
}
