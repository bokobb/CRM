class Counterparty {
  final int id;
  final String name;

  Counterparty({required this.id, required this.name});

  factory Counterparty.fromJson(Map<String, dynamic> json) => Counterparty(
        id: json['id'] as int,
        name: json['name'] as String,
      );
}
